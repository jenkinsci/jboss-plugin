package hudson.plugins.jboss;

import hudson.Util;

import org.kohsuke.stapler.DataBoundConstructor;

/**
 * This is composite configuration bean for all types of operations.
 * 
 * @author Juliusz Brzostek
 */
public class Operation {

	private final OperationEnum type;
	private final String properties;
	private final boolean stopOnFailure;
	
	/**
	 * Default constructor.
	 * 
	 * @param value type of the operation
	 * @param properties extra text properties
	 * @param stopOnFailure flag used by CHECK_DEPLOY operation
	 */
	@DataBoundConstructor
	public Operation(OperationEnum value, String properties, Boolean stopOnFailure) {
		this.type = value;
		this.properties = Util.fixEmptyAndTrim(properties);
		this.stopOnFailure = stopOnFailure!=null ? stopOnFailure : false;
	}

	public OperationEnum getType() {
		return this.type;
	}

	public String getProperties() {
		return this.properties;
	}

	public boolean isStopOnFailure() {
		return this.stopOnFailure;
	}
}
