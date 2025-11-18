package org.sagebionetworks.repo.manager.agent;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import org.sagebionetworks.cloudwatch.ProfileData;
import org.sagebionetworks.repo.model.agent.GridAgentSessionContext;
import org.sagebionetworks.repo.model.agent.SessionContext;
import org.sagebionetworks.util.ValidateArgument;

import software.amazon.awssdk.services.bedrockagentruntime.model.InvocationInputMember;

class AgentCloudwatchUtils {

    public enum AgentCloudwatchEventName {
        InvocationInput,
        InvocationInputFailure,
    }

    public static ProfileData generateCloudwatchProfileDataForInvocationInput(AgentCloudwatchEventName eventName, String namespace, SessionContext context, InvocationInputMember member, Exception optionalException){
        ValidateArgument.required(eventName, "eventName");
        ValidateArgument.required(member, "member");

        ProfileData event = new ProfileData();
        event.setNamespace(namespace);
        event.setName(eventName.toString());
        event.setValue(1.0);
        event.setUnit("Count");
        event.setTimestamp(new Date());

        Map<String, String> dimensions = new HashMap<>();
        if (context != null) {
            dimensions.put("SessionContextType", context.getConcreteType());
            if (context instanceof GridAgentSessionContext) {
                GridAgentSessionContext ctx = (GridAgentSessionContext) context;
                dimensions.put("GridSessionId", ctx.getGridSessionId());
                dimensions.put("GridAgentReplicaId", String.valueOf(ctx.getAgentsReplicaId()));
            }
        }
        if (member.functionInvocationInput() != null) {
            dimensions.put("InvocationType", "Function");
            dimensions.put("FunctionName", member.functionInvocationInput().function());
        }

        if (member.apiInvocationInput() != null) {
            dimensions.put("InvocationType", "API");
            dimensions.put("ApiPath", member.apiInvocationInput().apiPath());
            dimensions.put("HttpMethod", member.apiInvocationInput().httpMethod());
        }

        if (optionalException != null) {
            dimensions.put("ExceptionType", optionalException.getClass().getSimpleName());
            dimensions.put("ExceptionMessage", optionalException.getMessage());
        }

        event.setDimension(dimensions);

        return event;
    }
}