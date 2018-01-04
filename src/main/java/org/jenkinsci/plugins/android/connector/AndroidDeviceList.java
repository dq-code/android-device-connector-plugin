package org.jenkinsci.plugins.android.connector;

import com.google.common.collect.LinkedHashMultimap;
import com.google.common.collect.Multimap;
import com.google.common.collect.Multimaps;
import hudson.EnvVars;
import hudson.Extension;
import hudson.Launcher.LocalLauncher;
import hudson.model.Computer;
import hudson.model.ModelObject;
import hudson.model.RootAction;
import hudson.model.TaskListener;
import hudson.remoting.Callable;
import hudson.security.Permission;
import hudson.security.PermissionGroup;
import hudson.util.ArgumentListBuilder;
import hudson.util.IOException2;
import hudson.util.StreamTaskListener;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.HttpResponses;
import org.kohsuke.stapler.interceptor.RequirePOST;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.TimeUnit;

import static com.google.common.collect.Lists.newArrayList;
import static com.google.common.collect.Maps.newHashMap;

/**
 * Maintains the list of Android Devices connected to all the slaves.
 *
 * @author Kohsuke Kawaguchi
 * @author Nicolae Ghimbovschi (Android adaptation)
 */
@Extension
public class AndroidDeviceList implements RootAction, ModelObject {
    private volatile Multimap<Computer, AndroidDevice> devices = LinkedHashMultimap.create();

    /**
     * List of all the devices.
     */
    public Multimap<Computer, AndroidDevice> getDevices() {
        return Multimaps.unmodifiableMultimap(devices);
    }

    /**
     * Refresh all slaves in concurrently.
     */
    public void updateAll(TaskListener listener) {
        Jenkins.getInstance().checkPermission(Jenkins.ADMINISTER);

        Map<Future<List<AndroidDevice>>, Computer> futures = newHashMap();


        for (Computer c : Jenkins.getInstance().getComputers()) {
            try {
                EnvVars envVars = new EnvVars();

                try {
                    envVars = c.getEnvironment();
                    GlobalConfigurationImpl config = new GlobalConfigurationImpl();
                    String configData = config.getMapping();
                    if (configData != null) {
                        envVars.put("MAPPING", configData);
                    }

                } catch (Exception exception) {
                    listener.getLogger().println("Couldn't read configuration" + exception);

                }

                futures.put(c.getChannel().callAsync(new FetchTask(listener, envVars)), c);
            } catch (Exception e) {
                e.printStackTrace(listener.error("Failed to list up Android devices on" + c.getName()));
            }
        }

        Multimap<Computer, AndroidDevice> devices = LinkedHashMultimap.create();
        for (Entry<Future<List<AndroidDevice>>, Computer> e : futures.entrySet()) {
            Computer c = e.getValue();
            try {
                List<AndroidDevice> devs = e.getKey().get();
                for (AndroidDevice d : devs)
                    d.computer = c;
                devices.putAll(c, devs);
            } catch (Exception x) {
                x.printStackTrace(listener.error("Failed to list up Android devices on " + c.getName()));
            }
        }

        this.devices = devices;
    }

    public void update(Computer c, TaskListener listener) {
        Jenkins.getInstance().checkPermission(Jenkins.ADMINISTER);

        List<AndroidDevice> r = Collections.emptyList();
        if (c.isOnline()) {// ignore disabled slaves
            try {
                EnvVars envVars = c.getEnvironment();

                try {

                    GlobalConfigurationImpl config = new GlobalConfigurationImpl();
                    String configData = config.getMapping();
                    if (configData != null) {
                        envVars.put("MAPPING", configData);
                    }

                } catch (Exception exception) {
                    listener.getLogger().println("Couldn't read configuration" + exception);

                }


                r = c.getChannel().call(new FetchTask(listener, envVars));
                for (AndroidDevice dev : r) dev.computer = c;
            } catch (Exception e) {
                e.printStackTrace(listener.error("Failed to list up Android devices"));
            }
        }

        synchronized (this) {
            Multimap<Computer, AndroidDevice> clone = LinkedHashMultimap.create(devices);
            clone.removeAll(c);
            clone.putAll(c, r);
            devices = clone;
        }
    }

    public synchronized void remove(Computer c) {
        Multimap<Computer, AndroidDevice> clone = LinkedHashMultimap.create(devices);
        clone.removeAll(c);
        devices = clone;
    }

    public String getIconFileName() {
        return "/plugin/android-device-connector/icons/24x24/android.png";
    }

    public String getDisplayName() {
        return "Connected Android Devices";
    }

    public String getUrlName() {
        if (Jenkins.getInstance().hasPermission(READ))
            return "android-devices";
        else
            return null;
    }

    @RequirePOST
    public HttpResponse doRefresh() {
        updateAll(StreamTaskListener.NULL);
        return HttpResponses.redirectToDot();
    }

    /**
     * Maps {@link AndroidDevice} to URL space.
     */
    public AndroidDevice getDynamic(String token) {
        return getDevice(token);
    }

    public AndroidDevice getDevice(String udid) {
        for (AndroidDevice dev : devices.values())
            if (udid.equalsIgnoreCase(dev.getUniqueDeviceId()) || udid.equals(dev.getDeviceName()))
                return dev;
        return null;
    }

    /**
     * Retrieves {@link AndroidDevice}s connected to a machine.
     */
    private static class FetchTask implements Callable<List<AndroidDevice>, IOException> {
        private final TaskListener listener;
        private final EnvVars envVars;
        private final String mMappingRegex = "(.*)=(.*)";
        private final String mAdbDevicesRegex = "(.*)\\t+(.*)";
        private final String mAdbDeviceShellGetPropRegex = "\\[(.*)\\]: \\[(.*)\\]";

        private FetchTask(TaskListener listener, EnvVars envVars) {
            this.listener = listener;
            this.envVars = envVars;
        }

        private String getAdbCommand() {
            String adbCommand = "adb";

            try {
                Runtime.getRuntime().exec(adbCommand + " version");
            } catch (Exception any) {
                listener.error("ADB not in PATH. Will try to read ANDROID_HOME");

                if (envVars.containsKey("ANDROID_HOME")) {
                    adbCommand = envVars.get("ANDROID_HOME") + File.separator + "platform-tools" + File.separator + adbCommand;
                    listener.error("ANDROID_HOME found. " + adbCommand);

                } else {
                    listener.error("ANDROID_HOME not found.");
                }
            }

            return adbCommand;
        }

        //adb devices
        private ArgumentListBuilder getAndroidDevicesArgumentsList() {
            String command = getAdbCommand();
            ArgumentListBuilder adbDevicePropsCommand = new ArgumentListBuilder(command);
            adbDevicePropsCommand.add("devices");

            return adbDevicePropsCommand;
        }

        //adb -s <device_id> shell getprop
        private ArgumentListBuilder getAndroidDevicePropsArgumentsList(String deviceId) {
            String command = getAdbCommand();
            ArgumentListBuilder adbDevicePropsCommand = new ArgumentListBuilder(command);
            adbDevicePropsCommand.add("-s", (String) deviceId, "shell", "getprop");

            return adbDevicePropsCommand;
        }

        //adb restart
        private ArgumentListBuilder getRestartArgumentsList() {
            String command = getAdbCommand();
            ArgumentListBuilder adbDevicePropsCommand = new ArgumentListBuilder(command);
            adbDevicePropsCommand.add("kill-server");

            return adbDevicePropsCommand;
        }

        public List<AndroidDevice> call() throws IOException {
            List<AndroidDevice> androidDevicesList = newArrayList();


            //extract mappings from the config
            Properties mappings = new Properties();


            if (envVars.containsKey("MAPPING")) {
                mappings = extractOutputProperties(listener.getLogger(), (String) envVars.get("MAPPING"), mMappingRegex);
            }

            listener.getLogger().println("Getting devices");

            String connectedDevices = executeCommand(listener.getLogger(), getAndroidDevicesArgumentsList());
            Properties androidDevices = extractOutputProperties(listener.getLogger(), connectedDevices, mAdbDevicesRegex);

            Set keys = androidDevices.keySet();
            for (Object deviceId : keys) {
                String deviceProperties = executeCommand(listener.getLogger(), getAndroidDevicePropsArgumentsList((String) deviceId));
                Properties androidDeviceProperties = extractOutputProperties(listener.getLogger(), deviceProperties, mAdbDeviceShellGetPropRegex);

                androidDeviceProperties.put("UniqueDeviceID", deviceId);
                if (mappings.containsKey((String) deviceId)) {
                    androidDeviceProperties.put("AlternativeName", mappings.getProperty((String) deviceId));
                    mappings.remove((String) deviceId);
                }

                androidDevicesList.add(new AndroidDevice(androidDeviceProperties));
            }

            //add offline devices
            Set offline = mappings.keySet();
            for (Object device : offline) {

                Properties androidDeviceProperties = new Properties();
                androidDeviceProperties.put("UniqueDeviceID", (String) device);
                androidDeviceProperties.put("AlternativeName", mappings.getProperty((String) device) + " (unreachable)");

                androidDevicesList.add(new AndroidDevice(androidDeviceProperties));
            }

            return androidDevicesList;
        }

        public void restartADBCommand(PrintStream logger) throws IOException {
            logger.println("Restart adb server, adb kill-server");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try {
                int exit = new LocalLauncher(listener).launch()
                        .cmds(getRestartArgumentsList()).stdout(out).stderr(logger).join();
                logger.write(out.toByteArray());
                TimeUnit.SECONDS.sleep(8);
            }catch(InterruptedException e) {
                throw new IOException2("Interrupted while restart adb", e);
            }


        }

        public String executeCommand(PrintStream logger, ArgumentListBuilder arguments) throws IOException {
            logger.println("Executing " + arguments);
            int rerun = 5;
            int count = 1;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try {
                while (count < rerun) {
                    int exit = new LocalLauncher(listener).launch()
                            .cmds(arguments).stdout(out).stderr(logger).join();
                    if (exit != 0) {
                        logger.println(arguments + " failed to execute:" + exit);
                        logger.write(out.toByteArray());
                        logger.println();
                        restartADBCommand(logger);
                        count += 1;
                    } else if(arguments.toList().contains("devices")){
                        Pattern regexPattern = Pattern.compile(mAdbDevicesRegex);
                        Matcher matcher = regexPattern.matcher(out.toString("ISO-8859-1"));
                        if(!matcher.find()){
                            restartADBCommand(logger);
                            count += 1;
                        }else
                            break;
                    }else
                        break;
                }
            } catch (InterruptedException e) {
                throw new IOException2("Interrupted while listing up devices", e);
            }
            return new String(out.toByteArray(), "ISO-8859-1");
        }

        private Properties extractOutputProperties(PrintStream logger, String output, String regex) throws IOException {
            Properties props = new Properties();
            Pattern regexPattern = Pattern.compile(regex);
            Matcher matcher = regexPattern.matcher(output);

            while (matcher.find()) {
                props.put(matcher.group(1), matcher.group(2));
            }

            return props;
        }
    }

    public static final PermissionGroup GROUP = new PermissionGroup(AndroidDeviceList.class, Messages._AndroidDeviceList_PermissionGroup_Title());
    public static final Permission READ = new Permission(GROUP, "Read", Messages._AndroidList_ReadPermission(), Jenkins.READ);
    public static final Permission DEPLOY = new Permission(GROUP, "Deploy", Messages._AndroidList_DeployPermission(), Jenkins.ADMINISTER);
}
