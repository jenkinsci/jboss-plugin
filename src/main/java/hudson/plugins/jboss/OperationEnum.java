package hudson.plugins.jboss;

/**
 * Enum defines types of operations supported by plugin.
 * 
 * @author Juliusz Brzostek
 */
public enum OperationEnum {

    START_AND_WAIT,
    START,
    SHUTDOWN,
    CHECK_DEPLOY;
    
    public static OperationEnum[] all =
    	new OperationEnum[]{START_AND_WAIT, START, SHUTDOWN, CHECK_DEPLOY}; 

}
