package org.sagebionetworks.repo.manager.agent;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.sagebionetworks.cloudwatch.ProfileData;
import org.sagebionetworks.repo.model.agent.GridAgentSessionContext;

import software.amazon.awssdk.services.bedrockagentruntime.model.ApiInvocationInput;
import software.amazon.awssdk.services.bedrockagentruntime.model.FunctionInvocationInput;
import software.amazon.awssdk.services.bedrockagentruntime.model.InvocationInputMember;

class AgentCloudwatchUtilsTest {

    @Test
    public void testNullEventName() {
        InvocationInputMember member = InvocationInputMember.builder().build();

        assertThrows(IllegalArgumentException.class, () -> AgentCloudwatchUtils.generateCloudwatchProfileDataForInvocationInput(null, "ns", null, member, null));
    }

    @Test
    public void testNullInvocationInputMember() {
        assertThrows(IllegalArgumentException.class, () -> AgentCloudwatchUtils.generateCloudwatchProfileDataForInvocationInput(AgentCloudwatchUtils.AgentCloudwatchEventName.InvocationInput, "ns", null, null, null));
    }

    @Test
    public void testFunctionInvocation() {
        FunctionInvocationInput fin = FunctionInvocationInput.builder().actionGroup("action").function("myFunction").build();
        InvocationInputMember member = InvocationInputMember.builder().functionInvocationInput(fin).build();

        ProfileData pd = AgentCloudwatchUtils.generateCloudwatchProfileDataForInvocationInput(AgentCloudwatchUtils.AgentCloudwatchEventName.InvocationInput, "myNamespace", null, member, null);
        assertNotNull(pd);
        assertEquals("myNamespace", pd.getNamespace());
        assertEquals(AgentCloudwatchUtils.AgentCloudwatchEventName.InvocationInput.toString(), pd.getName());
        assertEquals(1.0, pd.getValue());
        assertEquals("Count", pd.getUnit());
        assertNotNull(pd.getTimestamp());

        Map<String, String> dims = pd.getDimension();
        assertNotNull(dims);
        assertEquals("Function", dims.get("InvocationType"));
        assertEquals("myFunction", dims.get("FunctionName"));
        assertFalse(dims.containsKey("ApiPath"));
        assertFalse(dims.containsKey("HttpMethod"));
        assertFalse(dims.containsKey("ExceptionType"));
    }

    @Test
    public void testApiInvocation() {
        ApiInvocationInput api = ApiInvocationInput.builder().actionGroup("ag").apiPath("/v1/resource").httpMethod("GET").build();
        InvocationInputMember member = InvocationInputMember.builder().apiInvocationInput(api).build();

        ProfileData pd = AgentCloudwatchUtils.generateCloudwatchProfileDataForInvocationInput(AgentCloudwatchUtils.AgentCloudwatchEventName.InvocationInput, "nsApi", null, member, null);
        Map<String, String> dims = pd.getDimension();
        assertEquals("API", dims.get("InvocationType"));
        assertEquals("/v1/resource", dims.get("ApiPath"));
        assertEquals("GET", dims.get("HttpMethod"));
    }

    @Test
    public void testInvocationInputException() {
        FunctionInvocationInput fin = FunctionInvocationInput.builder().actionGroup("action").function("gridFn").build();
        InvocationInputMember member = InvocationInputMember.builder().functionInvocationInput(fin).build();

        Exception ex = new RuntimeException("something bad");
        ProfileData pd = AgentCloudwatchUtils.generateCloudwatchProfileDataForInvocationInput(AgentCloudwatchUtils.AgentCloudwatchEventName.InvocationInputFailure, "gridNs", null, member, ex);

        Map<String, String> dims = pd.getDimension();
        assertEquals("RuntimeException", dims.get("ExceptionType"));
        assertEquals("something bad", dims.get("ExceptionMessage"));

    }

    @Test
    public void testGridContext() {
        GridAgentSessionContext gridContext = new GridAgentSessionContext();
        gridContext.setGridSessionId("grid-123");
        gridContext.setAgentsReplicaId(42L);

        FunctionInvocationInput fin = FunctionInvocationInput.builder().actionGroup("action").function("gridFn").build();
        InvocationInputMember member = InvocationInputMember.builder().functionInvocationInput(fin).build();

        ProfileData pd = AgentCloudwatchUtils.generateCloudwatchProfileDataForInvocationInput(AgentCloudwatchUtils.AgentCloudwatchEventName.InvocationInput, "gridNs", gridContext, member, null);

        Map<String, String> dims = pd.getDimension();
        assertEquals("org.sagebionetworks.repo.model.agent.GridAgentSessionContext", dims.get("SessionContextType"));
        assertEquals("grid-123", dims.get("GridSessionId"));
        assertEquals("42", dims.get("GridAgentReplicaId"));
    }

}