package hudson.plugins.jboss;

import hudson.EnvVars;
import hudson.Launcher;
import hudson.Util;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.plugins.jboss.JBossBuilder.ServerBean;
import hudson.util.ArgumentListBuilder;
import hudson.util.VariableResolver;

import java.io.IOException;

import org.apache.commons.io.output.NullOutputStream;

/**
 * Utility class for dealing with jboss command on OS system level.
 * 
 * @author Juliusz Brzostek
 *
 */
public class CommandsUtils {

	private CommandsUtils() {
		// utility class cannot be instantiated
	}

    /**
     * Starts given server.
     * Method is not waiting.
     * 
     * @param server server to start
     * @param extraProperties extra properties for run command
     * @param launcher system command luncher
     * @param listener {@link BuildListener} for logging purpose
     * @return true if everything gone fine, false if any error occurred 
     */
	@SuppressWarnings("unchecked")
	public static boolean start(ServerBean server, String extraProperties,
			AbstractBuild build, Launcher launcher,
			BuildListener listener) throws IOException, InterruptedException {
		
		String startCommand = server.getHomeDir() + "/bin/"
			 + (launcher.isUnix() ? "run.sh" : "run.bat");
		
        ArgumentListBuilder args = new ArgumentListBuilder();
        args.add(startCommand);
        args.add("-c", server.getServerName());
        if(!launcher.isUnix()) {
            args = new ArgumentListBuilder().add("cmd.exe","/C").addQuoted(args.toStringWithQuote());
        }

    	EnvVars env = build.getEnvironment(listener);
        VariableResolver<String> vr = build.getBuildVariableResolver();
        String properties = env.expand(extraProperties);
        args.addKeyValuePairsFromPropertyString("-D",properties,vr);

        try {
        	launcher.launch()
        				.stderr(listener.getLogger())
        				.stdout(new NullOutputStream())
        				.cmds(args)
        				.pwd(server.getHomeDir() + "/bin")
        				.start();
            return true;
        } catch (Exception e) {
        	if (e instanceof IOException) {
        		Util.displayIOException((IOException)e,listener);
        	}
            e.printStackTrace( listener.fatalError("Error during execution.") );
            return false;
		}
    }

    /**
     * Stops given server.
     * 
     * @param server {@link ServerBean} to be stopped
     * @param launcher system command luncher
     * @param listener {@link BuildListener} for logging purpose
     * @return true if everything gone fine, false if any error occurred 
     */
    public static boolean stop(ServerBean server, Launcher launcher, BuildListener listener) {

		String stopCommand = server.getHomeDir() + "/bin/"
				+ (launcher.isUnix() ? "shutdown.sh" : "shutdown.bat");

		ArgumentListBuilder args = new ArgumentListBuilder();
        args.add(stopCommand);
        
        String jndiUrl = "jnp://127.0.0.1:" + server.getJndiPort(); //jnp://127.0.0.1:1099
        
        args.add("-s", jndiUrl, "-S");
        if(!launcher.isUnix()) {
            args = new ArgumentListBuilder().add("cmd.exe","/C").addQuoted(args.toStringWithQuote());
        }
        
        try {
        	launcher.launch()
        				.stderr(listener.getLogger())
        				.stdout(new NullOutputStream())
        				.cmds(args)
        				.pwd(server.getHomeDir() + "/bin")
        				.join();
            return true;
        } catch (Exception e) {
        	if (e instanceof IOException) {
        		Util.displayIOException((IOException)e,listener);
        	}
            e.printStackTrace( listener.fatalError("Error during execution.") );
            return false;
		}
    }

}
