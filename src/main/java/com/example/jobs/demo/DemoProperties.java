package com.example.jobs.demo;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "demo")
public class DemoProperties {
  private boolean enabled;
  private boolean trustProxyHeader;
  private int perClientBurstSeconds = 3;
  private int perClientHourlyLimit = 30;
  private int globalPendingRunningCap = 60;

  public boolean isEnabled() { return enabled; }
  public void setEnabled(boolean enabled) { this.enabled = enabled; }
  public boolean isTrustProxyHeader() { return trustProxyHeader; }
  public void setTrustProxyHeader(boolean trustProxyHeader) { this.trustProxyHeader = trustProxyHeader; }
  public int getPerClientBurstSeconds() { return perClientBurstSeconds; }
  public void setPerClientBurstSeconds(int value) { perClientBurstSeconds = value; }
  public int getPerClientHourlyLimit() { return perClientHourlyLimit; }
  public void setPerClientHourlyLimit(int value) { perClientHourlyLimit = value; }
  public int getGlobalPendingRunningCap() { return globalPendingRunningCap; }
  public void setGlobalPendingRunningCap(int value) { globalPendingRunningCap = value; }
}
