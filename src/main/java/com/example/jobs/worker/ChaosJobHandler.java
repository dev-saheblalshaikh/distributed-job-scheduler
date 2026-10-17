package com.example.jobs.worker;
import org.springframework.stereotype.Component; import tools.jackson.databind.JsonNode;
@Component public class ChaosJobHandler implements JobHandler { public String type(){return "CHAOS";} public void execute(JsonNode p){ if(p!=null && p.path("fail").asBoolean(false)) throw new IllegalStateException("chaos requested failure"); long seconds=p==null?0:p.path("sleepSeconds").asLong(0); try { Thread.sleep(seconds*1000); } catch(InterruptedException e){Thread.currentThread().interrupt(); throw new IllegalStateException("interrupted",e);} } }
