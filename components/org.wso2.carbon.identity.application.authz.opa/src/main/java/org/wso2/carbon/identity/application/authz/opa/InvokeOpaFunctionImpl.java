/*
 * Copyright (c) 2020, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.wso2.carbon.identity.application.authz.opa;

import org.apache.commons.lang.StringUtils;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.conn.ConnectTimeoutException;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.util.EntityUtils;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import org.wso2.carbon.identity.application.authentication.framework.AsyncProcess;
import org.wso2.carbon.identity.application.authentication.framework.config.model.graph.JsGraphBuilder;

import org.wso2.carbon.identity.application.authentication.framework.config.model.graph.js.JsAuthenticatedUser;
import org.wso2.carbon.identity.application.authentication.framework.config.model.graph.js.JsStep;
import org.wso2.carbon.identity.application.authentication.framework.config.model.graph.js.JsSteps;
import org.wso2.carbon.identity.application.authentication.framework.config.model.graph.js.JsClaims;
import org.wso2.carbon.identity.application.authentication.framework.config.model.graph.js.JsAuthenticationContext;
import org.wso2.carbon.identity.application.authentication.framework.util.FrameworkConstants;
import org.wso2.carbon.identity.application.authz.opa.internal.OPAFunctionsServiceHolder;
import org.wso2.carbon.identity.application.authz.opa.util.OPAConstants;
import org.wso2.carbon.identity.claim.metadata.mgt.exception.ClaimMetadataException;
import org.wso2.carbon.identity.claim.metadata.mgt.model.LocalClaim;
import org.wso2.carbon.identity.conditional.auth.functions.common.utils.Constants;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Arrays;
import java.util.Collection;

import static org.apache.http.HttpHeaders.ACCEPT;
import static org.apache.http.HttpHeaders.CONTENT_TYPE;

/**
 * Implementation of InvokeOpaFunction.
 */
public class InvokeOpaFunctionImpl implements InvokeOpaFunction {

    private final CloseableHttpClient client;

    public InvokeOpaFunctionImpl() {

        RequestConfig config = RequestConfig.custom()
                .setConnectTimeout(OPAConstants.TIME)
                .setConnectionRequestTimeout(OPAConstants.TIME)
                .setSocketTimeout(OPAConstants.TIME)
                .build();
        client = HttpClientBuilder.create().setDefaultRequestConfig(config).build();
    }

    @Override
    public void invokeOPA(String epUrl, Map<String, Object> payload, Map<String, String> options,
                          Map<String, Object> events) {

        JsAuthenticatedUser user = null;
        String userStoreDomain = null;

        if (payload != null) {
            JsAuthenticationContext jsContext = (JsAuthenticationContext) (payload.get(OPAConstants.CONTEXT));
            if ((jsContext.getMember(FrameworkConstants.JSAttributes.JS_STEPS)) != null) {
                JsStep slot = (JsStep) (((JsSteps) jsContext
                        .getMember(FrameworkConstants.JSAttributes.JS_STEPS))
                        .getSlot(1));
                if (slot != null) {
                    user = (JsAuthenticatedUser) slot.getMember(FrameworkConstants.JSAttributes.JS_AUTHENTICATED_SUBJECT);
                    userStoreDomain = (String) user.getMember(FrameworkConstants.JSAttributes.JS_USER_STORE_DOMAIN);
                }
            }
        }

        JSONObject userClaims = null;
        List roles = new ArrayList<>();

        if (user != null) {
            if (Boolean.parseBoolean(options.get(OPAConstants.SEND_CLAIMS))) {
                userClaims = getClaims(user);
            } else if (Boolean.parseBoolean(options.get(OPAConstants.SEND_ROLES))) {
                roles = getUserRoles(user);
            }
        }

        JSONObject finalClaims = userClaims;
        List finalRoles = roles;
        String finalStoreDomain = userStoreDomain;
        JsAuthenticatedUser finalUser = user;

        AsyncProcess asyncProcess = new AsyncProcess((context, asyncReturn) -> {
            JSONObject json = null;
            int responseCode;
            String outcome;

            HttpPost request = new HttpPost(epUrl);
            try {
                request.setHeader(ACCEPT, OPAConstants.TYPE_APPLICATION_JSON);
                request.setHeader(CONTENT_TYPE, OPAConstants.TYPE_APPLICATION_JSON);

                JSONObject input = buildInput(payload, finalClaims, finalRoles, finalStoreDomain, finalUser);
                request.setEntity(new StringEntity(input.toJSONString()));

                try (CloseableHttpResponse response = client.execute(request)) {
                    responseCode = response.getStatusLine().getStatusCode();

                    if (responseCode == 200) {
                        outcome = Constants.OUTCOME_SUCCESS;
                        String jsonString = EntityUtils.toString(response.getEntity());
                        JSONParser parser = new JSONParser();
                        json = (JSONObject) parser.parse(jsonString);
                    } else {
                        outcome = Constants.OUTCOME_FAIL;
                    }
                }

            } catch (ConnectTimeoutException e) {
                OPAConstants.LOG.error("Error while waiting to connect to " + epUrl, e);
                outcome = Constants.OUTCOME_TIMEOUT;
            } catch (SocketTimeoutException e) {
                OPAConstants.LOG.error("Error while waiting for data from " + epUrl, e);
                outcome = Constants.OUTCOME_TIMEOUT;
            } catch (IOException e) {
                OPAConstants.LOG.error("Error while calling endpoint. ", e);
                outcome = Constants.OUTCOME_FAIL;
            } catch (ParseException e) {
                OPAConstants.LOG.error("Error while parsing response. ", e);
                outcome = Constants.OUTCOME_FAIL;
            }
            asyncReturn.accept(context, json != null ? json : Collections.emptyMap(), outcome);
        });
        JsGraphBuilder.addLongWaitProcess(asyncProcess, events);
    }

    private JSONObject buildInput(Map<String, Object> payload, JSONObject finalClaims,
                                  List finalRoles, String finalStoreDomain, JsAuthenticatedUser finalUser) {

        JSONObject finalJsonObject = new JSONObject();
        if (payload != null) {
            for (Map.Entry<String, Object> dataElements : payload.entrySet()) {
                if (dataElements.getValue() != payload.get(OPAConstants.CONTEXT)) {
                    finalJsonObject.put(dataElements.getKey(), dataElements.getValue());
                }
            }
        }

        JSONObject userJsonObject = new JSONObject();
        JSONObject input = new JSONObject();

        userJsonObject.put(OPAConstants.CLAIMS, finalClaims);
        userJsonObject.put(OPAConstants.ROLES, finalRoles);
        userJsonObject.put(OPAConstants.USER_STORE_DOMAIN, finalStoreDomain);
        userJsonObject.put(OPAConstants.USER_CONTEXT, getUserDetails(finalUser));
        finalJsonObject.put(OPAConstants.USER, userJsonObject);
        input.put(OPAConstants.INPUT, finalJsonObject);

        return input;
    }

    private JSONObject getClaims(JsAuthenticatedUser user) {

        JSONObject claims = new JSONObject();
        String tenantDomain = (String) user.getMember(FrameworkConstants.JSAttributes.JS_TENANT_DOMAIN);

        List<LocalClaim> localClaims = null;
        try {
            OPAFunctionsServiceHolder functionServiceHolder = OPAFunctionsServiceHolder.getInstance();
            localClaims = functionServiceHolder.getClaimMetadataManagementService().getLocalClaims(tenantDomain);
        } catch (ClaimMetadataException e) {
            OPAConstants.LOG.error("Error while getting local claims in tenant domain : " + tenantDomain);
        }
        if (localClaims != null) {
            for (LocalClaim localClaim : localClaims) {
                String claimUri = localClaim.getClaimURI();
                JsClaims jsclaims = ((JsClaims) user.getMember(FrameworkConstants.JSAttributes.JS_LOCAL_CLAIMS));
                String claimValue = (String) (jsclaims.getMember(claimUri));
                if (StringUtils.isNotBlank(claimValue)) {
                    claims.put(claimUri, claimValue);
                }
            }
        }
        return claims;
    }

    private JSONObject getUserDetails(JsAuthenticatedUser user) {

        JSONObject uerDetails = new JSONObject();
        String jsAuthSubject = FrameworkConstants.JSAttributes.JS_AUTHENTICATED_SUBJECT_IDENTIFIER;
        String authenticatedSubjectIdentifier = (String) user.getMember(jsAuthSubject);
        uerDetails.put(OPAConstants.JS_AUTHENTICATED_SUBJECT_IDENTIFIER, authenticatedSubjectIdentifier);
        String userName = (String) user.getMember(FrameworkConstants.JSAttributes.JS_USERNAME);
        uerDetails.put(OPAConstants.JS_USERNAME, userName);
        String tenantDomain = (String) user.getMember(FrameworkConstants.JSAttributes.JS_TENANT_DOMAIN);
        uerDetails.put(OPAConstants.JS_TENANT_DOMAIN, tenantDomain);

        return uerDetails;
    }

    private List getUserRoles(JsAuthenticatedUser user) {

        Object userRoles = user.getMember(FrameworkConstants.JSAttributes.JS_LOCAL_ROLES);
        List list = new ArrayList<>();
        if (userRoles.getClass().isArray()) {
            list = Arrays.asList((Object[]) userRoles);
        } else if (userRoles instanceof Collection) {
            list = new ArrayList<>((Collection<?>) userRoles);
        }
        return list;
    }
}
