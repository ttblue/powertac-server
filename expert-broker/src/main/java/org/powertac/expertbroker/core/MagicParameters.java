package org.powertac.expertbroker.core;

import java.io.File;

/**
 * Class to store the values of different parameters used in
 * broker transactions,tariffs,etc.
 * 
 */
public class MagicParameters {

	private double p1;
	private double p2;
	private double p3;
	private double p4;
	private double p5;

	/**
	 * Constructor from file.
	 */
	public MagicParameters(File config) {
		BrokerPropertiesService brokerConfig = new BrokerPropertiesService ();
		if (config != null && config.canRead())
			brokerConfig.setUserConfig(config);
		
		// Do so for the other parameters.
		p1 = brokerConfig.getDoubleProperty("p1", 0.0);

	}
	
	public void updateParameters(MagicParameters mp) {
		// Do this for the other parameters too.
		p1 = mp.getParameter1();
	}
	
	// Do this for other parameters too.
	public double getParameter1() {
		return p1;
	}
}