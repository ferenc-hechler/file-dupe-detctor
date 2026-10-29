package com.filedupedetector.pcloud;

import java.io.IOException;

import com.pcloud.sdk.ApiClient;
import com.pcloud.sdk.ApiError;
import com.pcloud.sdk.Authenticators;
import com.pcloud.sdk.PCloudSdk;
import com.pcloud.sdk.UserInfo;

/**
 * 
 * @author feri
 */

public class PCloudApiClient {

	private static PCloudConfig config;
	public static PCloudConfig getConfig() {
		if (config == null) {
			config = new PCloudConfig();
		}
		return config;
	}

	private static ApiClient internApiClient;
	public static synchronized ApiClient getApiClient() {
		if (internApiClient == null) {
			internApiClient = PCloudSdk.newClientBuilder()
					.authenticator(Authenticators.newOAuthAuthenticator(getConfig().getAccessToken()))
					.apiHost(getConfig().getApiHost())
					// Other configuration...
					.create();
		}
		return internApiClient;
	}
	public static synchronized ApiClient getApiClient(String configFilename) {
		PCloudApiClient.setConfigFilename(configFilename);
		return getApiClient();
	}
	public static synchronized void shutdownApiClient() {
		if (internApiClient == null) {
			return;
		}
		try {
			internApiClient.shutdown();
		}
		catch (Exception e) {
			e.printStackTrace();
		}
		internApiClient = null;
	}
	public static void setConfigFilename(String configFilename) {
		PCloudConfig.setConfigFilename(configFilename);
	}
	
	public static String getPCloudEnv() {
		return getConfig().getPCloudEnv();
	}

	public static UserInfo getUserInfo() {
		
		try {
			UserInfo userInfo = getApiClient().getUserInfo().execute();
			return userInfo;
		} catch (IOException | ApiError e) {
			throw new RuntimeException("error getting user info", e);
		}
	}
	public static String getAPIHost() {
		return getConfig().getApiHost();
	}

}
