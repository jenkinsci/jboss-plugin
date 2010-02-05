package hudson.plugins.jboss;
import java.io.File;
import java.io.IOException;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import javax.management.MBeanServerConnection;
import javax.management.ObjectName;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.servlet.ServletException;

import hudson.CopyOnWrite;
import hudson.EnvVars;
import hudson.Extension;
import hudson.Launcher;
import hudson.Util;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.util.ArgumentListBuilder;
import hudson.util.FormValidation;
import hudson.util.VariableResolver;

import net.sf.json.JSONArray;
import net.sf.json.JSONObject;

import org.apache.commons.io.output.NullOutputStream;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

/**
 * This plugin is for simple management of JBoss instances.
 *
 * @author Juliusz Brzostek
 */
public class JBossBuilder extends Builder {

    private final ServerBean server;

    private final DescriptorImpl.Operation operation;

    private final String properties;
    
    @DataBoundConstructor
    public JBossBuilder(DescriptorImpl.Operation operation, String serverName, String properties) {
    	this.operation = operation;
    	this.properties = Util.fixEmptyAndTrim(properties);;
    	
    	ServerBean localServerBean = null;
    	
    	for (ServerBean bean : getDescriptor().getServers()) {
    		if (bean.getServerName().equals(serverName)){
    			localServerBean = bean;
    			break;
    		}
    	}
    	
    	this.server = localServerBean;
    }

    public ServerBean getServer() {
        return this.server;
    }

    public DescriptorImpl.Operation getOperation() {
    	return this.operation;
    }
    
    public String getProperties() {
    	return this.properties;
    }
    
    @Override
    public boolean perform(AbstractBuild build, Launcher launcher, BuildListener listener) throws IOException, InterruptedException{
    	
    	if (server == null || operation == null) {
    		listener.fatalError("Wrong configuration of plugin. Step error.");
    		return false;
    	}

    	ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader(); 
    	Thread.currentThread().setContextClassLoader(getClass().getClassLoader());
    	try {
	    	switch (operation) {
	    		case START_AND_WAIT:
	    			if (checkStatus(listener, 3, true)) {
	    				listener.getLogger().println("JBoss AS already started.");
	    				return true;
	    			}
	    			boolean ret = start(build, launcher, listener) && checkStatus(listener, 15, false);
	    			if (ret) {
	        			listener.getLogger().println("JBoss AS started!");
	    			} else {
	        			listener.getLogger().println("JBoss AS is not stared before timeout has expired!");
	    			}
	    			return ret;
	    		case START:
	    			if (checkStatus(listener, 3, true)) {
	    				listener.getLogger().println("JBoss AS already started.");
	    				return true;
	    			}
	    			return start(build, launcher, listener);
	    		case SHUTDOWN:
	    			if (!checkStatus(listener, 3, true)) {
	    				listener.getLogger().println("JBoss AS is not working.");
	    				return true;
	    			}
	    			return stop(launcher, listener);
	    		default:
	    			listener.fatalError("Uexpected type of operation.");
	    			return false;
	    	}
    	} finally {
        	Thread.currentThread().setContextClassLoader(contextClassLoader);
    	}
    }

    /**
     * Stops given server.
     * 
     * @param launcher
     * @param listener
     * @return
     */
    private boolean stop(Launcher launcher, BuildListener listener) {
        ArgumentListBuilder args = new ArgumentListBuilder();
        args.add(launcher.isUnix() ? "shutdown.sh" : "shutdown.bat");
        
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
        				.pwd(getDescriptor()
        				.getHomeDir() + "/bin")
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
     * Starts given server. Not waits.
     * 
     * @param launcher
     * @param listener
     * @return
     */
	private boolean start(AbstractBuild build, Launcher launcher,
			BuildListener listener) throws IOException, InterruptedException {
		
        ArgumentListBuilder args = new ArgumentListBuilder();
        args.add(launcher.isUnix() ? "run.sh" : "run.bat");
        args.add("-c", server.getServerName());
        if(!launcher.isUnix()) {
            args = new ArgumentListBuilder().add("cmd.exe","/C").addQuoted(args.toStringWithQuote());
        }

    	EnvVars env = build.getEnvironment(listener);
        VariableResolver<String> vr = build.getBuildVariableResolver();
        String properties = env.expand(this.properties);
        args.addKeyValuePairsFromPropertyString("-D",properties,vr);

        try {
        	launcher.launch()
        				.stderr(listener.getLogger())
        				.stdout(new NullOutputStream())
        				.cmds(args)
        				.pwd(getDescriptor()
        				.getHomeDir() + "/bin")
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
     * Tries to connect to naming service and checks status of the server.
     * If server is not started waits 'timeout'
     * seconds for start checking every second.
     * 
     * @param listener
     */
	private boolean checkStatus(final BuildListener listener,
			final int timeout, boolean ignoreErrors) {

		boolean started = false;
		try {
			InitialContext ctx = getInitialContext();
	
			MBeanServerConnection server = getMBeanServer(ctx, listener, timeout);
			
			// Wait until server startup is complete
			long startTime = System.currentTimeMillis();
			while (!started
					&& (System.currentTimeMillis() - startTime < timeout * 1000)) {
				try {
					Thread.sleep(1000);
					started = isStarted(server);
				} catch (Exception e) {
					throw new RuntimeException("Unable to wait: " + e.getMessage(),
						e);
				}
			}
		} catch (RuntimeException e) {
			if (!ignoreErrors) {
				throw e;
			}
		}
		return started;
    }

    /**
     * Trying to connect to naming service.
     * 
     * @param ctx, {@link InitialContext} used to lookup.
     * @param listener, used only for logging purpose
     * @return, server connection or exception will thrown if failed
     */
    private MBeanServerConnection getMBeanServer(
    		final InitialContext ctx, final BuildListener listener, int timeout) {
    	
		MBeanServerConnection server = null;
		NamingException ne = null;
		int retryWait = 1000;
		for (int i = 0; i < timeout; ++i) {
			try {
				Thread.sleep(retryWait);
				server = (MBeanServerConnection) ctx
						.lookup("jmx/invoker/RMIAdaptor");
				break;
			} catch (NamingException e) {
				ne = e;
			} catch (InterruptedException e) {
				listener.getLogger().println(
						"Thread interrupted while waiting for MBean connection: "
								+ e.getMessage());
				e.printStackTrace();
				break;
			}
		}

         if ( server == null ) {
             throw new RuntimeException( "Unable to get JBoss JMX MBean connection in 60 seconds: " + ne.getMessage(), ne );
         }
         
         return server;
    }
    
    /**
     * Checks if Server is up for given MBean.
     * 
     * @param server, given {@link MBeanServerConnection}
     * @return true if server is up, false otherwise
     * @throws Exception, a few types of exception can be thrown.
     */
	private boolean isStarted(MBeanServerConnection server) throws Exception {
		ObjectName serverMBeanName = new ObjectName("jboss.system:type=Server");
		return ((Boolean) server.getAttribute(serverMBeanName, "Started"))
				.booleanValue();
	}

	/**
	 * Gets {@link InitialContet} for current server.
	 * 
	 * @return Obtained InitialContext, or RuntimeException will thrown.
	 */
	private InitialContext getInitialContext() {
		// currently hard-coded localhost, in future it can be more flexible
		String hostName = "127.0.0.1";
		
		Properties env = new Properties();
		try {
			env.put("java.naming.factory.initial",
					"org.jnp.interfaces.NamingContextFactory");
			env.put("java.naming.factory.url.pkgs",
					"org.jboss.naming:org.jnp.interfaces");
			env.put("java.naming.provider.url", hostName + ":" + server.getJndiPort());
			return new InitialContext(env);
		} catch (NamingException e) {
			throw new RuntimeException(
					"Unable to instantiate naming context: " + e.getMessage(),
					e);
		}
	}
    
    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl)super.getDescriptor();
    }

    /**
     * Descriptor for {@link JBossBuilder}. Used as a singleton.
      */
    @Extension
    public static final class DescriptorImpl extends BuildStepDescriptor<Builder> {

    	/**
         * Home directory of JBoss.
         */
        private String homeDir;

        /**
         * List of defined servers.
         */
        @CopyOnWrite
        private List<ServerBean> servers = new ArrayList<ServerBean>();

        public DescriptorImpl() {
            load();
        }

        /**
         * Performs validation of JBoss home directory
         *
         * @value
         *      This parameter receives the value that the user has typed.
         * @return
         *      Indicates the outcome of the validation. This is sent to the browser.
         */
		public FormValidation doCheckHomeDir(
				@QueryParameter final String jbossHomeString)
					throws IOException,	ServletException {
        	
            if(jbossHomeString == null || jbossHomeString.length() == 0) { 
                return FormValidation.error("Please set path to JBoss home.");
            }
        
            File jbossHomeFile = new File(jbossHomeString);
            
            if (!jbossHomeFile.exists()) {
            	return FormValidation.error("Path doesn't exist.");
            }

            if (!jbossHomeFile.isDirectory()) {
            	return FormValidation.error("Path is not valid directory.");
            }

            for (String subDir : new String[]{"bin", "server"}) {
            	File subFile = new File(jbossHomeFile, subDir);
            	if (!subFile.exists() || !subFile.isDirectory()) {
            		return FormValidation.error("It's not look like correct JBoss home directory.");
            	}
            }
            
            return FormValidation.ok();
        }

        public FormValidation doCheckServerName(
        		@QueryParameter final String value)
        			throws IOException, ServletException {

            if(value == null || value.length() == 0) { 
                return FormValidation.error("Server name can not be empty.");
            }
            
            return FormValidation.ok();
        }

        public FormValidation doCheckJndiPort(
        		@QueryParameter final String value)
        			throws IOException, ServletException {

            if(value == null || value.length() == 0) { 
                return FormValidation.error("JNDI port is mandatory.");
            }
            
            int jndiPortNr = 0;
            try {
            	jndiPortNr = Integer.parseInt(value);
            } catch (NumberFormatException e) {
            	return FormValidation.error("JNDI port is valid number.");
            }
            
            if (jndiPortNr <= 1024) {
            	return FormValidation.error("JNDI port can not be lowest then 1024.");
            }
            
            return FormValidation.ok();
        }
		
        @Override
        public boolean isApplicable(Class<? extends AbstractProject> aClass) {
            return true;
        }

        /**
         * Builder name.
         */
        public String getDisplayName() {
            return "JBoss Management";
        }

        @Override
        public boolean configure(StaplerRequest req, JSONObject parameters) throws FormException {

        	homeDir = parameters.getString("homeDir");

        	servers.clear();
        	
            JSONObject optServersObject = parameters.optJSONObject("servers");
            if (optServersObject != null) {
            	servers.add(new ServerBean(
            							optServersObject.getString("serverName"),
            							optServersObject.getInt("jndiPort")));
            } else {
            	JSONArray optServersArray = parameters.optJSONArray("servers");
            	if (optServersArray != null) {
            		for (int i=0; i < optServersArray.size(); i++) {
            			JSONObject serverObject = (JSONObject) optServersArray.get(i);
                    	servers.add(new ServerBean(
    							serverObject.getString("serverName"),
    							serverObject.getInt("jndiPort")));
            		}
            	}
            }
            
            save();
            return super.configure(req, parameters);
        }

        public String getHomeDir() {
            return homeDir;
        }
        
        public List<ServerBean> getServers() {
        	return this.servers;
        }

        public Operation[] getOperations() {
        	return Operation.all;
        }

        enum Operation {
        	
            START_AND_WAIT,
            START,
            SHUTDOWN;
            
            private static Operation[] all =
            	new Operation[]{START_AND_WAIT, START, SHUTDOWN}; 
        }
}
    
    public static class ServerBean {
    	private final String serverName;
    	private final int jndiPort;
    	
    	public ServerBean(final String serverName, final int jndiPort) {
    		this.serverName = serverName;
    		this.jndiPort =jndiPort;
    	}
    	
    	public String getServerName() {
    		return this.serverName;
    	}
    	
    	public int getJndiPort() {
    		return this.jndiPort;
    	}
    	
    	@Override
    	public String toString() {
    		return new StringBuilder()
    				.append("ServerBean=")
    				.append(serverName)
    				.append(":")
    				.append(jndiPort).toString();
    	}
    }
}
