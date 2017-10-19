package com.wso2telco.gsma.authenticators.voice;

import com.wso2telco.Util;
import com.wso2telco.gsma.authenticators.AuthenticatorException;
import com.wso2telco.gsma.authenticators.Constants;
import com.wso2telco.gsma.authenticators.DBUtils;
import com.wso2telco.gsma.authenticators.util.AdminServiceUtil;
import com.wso2telco.gsma.authenticators.util.AuthenticationContextHelper;
import com.wso2telco.gsma.authenticators.util.BasicFutureCallback;
import com.wso2telco.gsma.authenticators.util.UserProfileManager;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.client.HttpClient;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.util.EntityUtils;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.wso2.carbon.identity.application.authentication.framework.AbstractApplicationAuthenticator;
import org.wso2.carbon.identity.application.authentication.framework.AuthenticatorFlowStatus;
import org.wso2.carbon.identity.application.authentication.framework.LocalApplicationAuthenticator;
import org.wso2.carbon.identity.application.authentication.framework.config.model.ApplicationConfig;
import org.wso2.carbon.identity.application.authentication.framework.context.AuthenticationContext;
import org.wso2.carbon.identity.application.authentication.framework.exception.AuthenticationFailedException;
import org.wso2.carbon.identity.application.authentication.framework.exception.LogoutFailedException;
import org.wso2.carbon.identity.application.authentication.framework.util.FrameworkUtils;
import org.wso2.carbon.identity.user.registration.stub.UserRegistrationAdminServiceIdentityException;
import org.wso2.carbon.user.api.UserStoreException;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.rmi.RemoteException;
import java.sql.SQLException;
import java.util.Map;

/**
 * Created by sheshan on 4/26/17.
 */
public class VoiceCallAuthenticator extends AbstractApplicationAuthenticator
        implements LocalApplicationAuthenticator {

    private static final String MSISDN = "msisdn";
    private static final String CLIENT_ID = "relyingParty";
    private static final String IS_FLOW_COMPLETED = "isFlowCompleted";
    private static Log log = LogFactory.getLog(VoiceCallAuthenticator.class);
    private static final String APPLICATION_JSON = "application/json";

    private final static String isUserEnrolledUrl = "https://poc.vsservic.es/pwr/isUserActive";
    private final static String verifyUserUrl = "https://poc.vsservic.es/pwr/authenticateUser";
    private final static String onBoardUserUrl = "https://poc.vsservic.es/pwr/onboardUser";


    @Override
    public AuthenticatorFlowStatus process(HttpServletRequest request, HttpServletResponse response, AuthenticationContext context) throws AuthenticationFailedException, LogoutFailedException {

        log.info("Initiating process from Voice Call Authentication");

        super.process(request, response, context);
        if (context.isLogoutRequest()) {
            return AuthenticatorFlowStatus.SUCCESS_COMPLETED;
        }
        else {
            boolean isFlowCompleted = (boolean) context.getProperty(IS_FLOW_COMPLETED);

            if (isFlowCompleted) {
                return AuthenticatorFlowStatus.SUCCESS_COMPLETED;
            } else {
                return AuthenticatorFlowStatus.INCOMPLETE;
            }
        }

    }


    @Override
    protected void initiateAuthenticationRequest(HttpServletRequest request, HttpServletResponse response, AuthenticationContext context) throws AuthenticationFailedException {
        super.initiateAuthenticationRequest(request, response, context);
        log.info("Initiating authentication request from Voice Call Authentication");

        String queryParams = FrameworkUtils.getQueryStringWithFrameworkContextId(context.getQueryParams(),
                context.getCallerSessionKey(), context.getContextIdentifier());
        ApplicationConfig applicationConfig = context.getSequenceConfig().getApplicationConfig();
        Map<String, String> paramMap = Util.createQueryParamMap(queryParams);
        String msisdn = (String) context.getProperty(MSISDN);
        String clientId = paramMap.get(CLIENT_ID);
        String applicationName = applicationConfig.getApplicationName();
        String sessionKey = context.getCallerSessionKey();

        log.info("MSISDN : " + msisdn);
        log.info("Client ID : " + clientId);
        log.info("Application name : " + applicationName);
        log.info("sessionKey  : " + sessionKey);

        ValidSoftJsonHelper validSoftJsonHelper = new ValidSoftJsonHelper();
        validSoftJsonHelper.setIsUserActiveRequestJson(sessionKey, msisdn);
        validSoftJsonHelper.setUserRegistrationAndAuthenticationJson(sessionKey, msisdn);
        StringEntity postData = null;
        StringEntity verifyAndRegistartionPostData = null;
        try {
            postData = new StringEntity(validSoftJsonHelper.getIsUserActiveRequestJson());
            verifyAndRegistartionPostData = new StringEntity(validSoftJsonHelper.getUserRegistrationAndAuthenticationJson());
            log.info("Json Object : " + postData);
        } catch (UnsupportedEncodingException e) {
            log.error("Error occurred due to UnsupportedEncoding Exception", e);
        }

        String url = "/authenticationendpoint/mcx-user-registration/ivr_waiting" + "?" + queryParams + "&redirect_uri=" +
                (String) context.getProperty("redirectURI") + "&authenticators="
                + getName() + ":" + "LOCAL" + ""+"&sessionDataKey=" + sessionKey;

        try {
            boolean isUserEnrolledInValidSoft = verifyIsUserActiveFromValidSoftServer(isUserEnrolledUrl, postData);
            context.setProperty(IS_FLOW_COMPLETED, false);
            DBUtils.insertAuthFlowStatus(msisdn, Constants.STATUS_PENDING, context.getContextIdentifier());
            if (isUserEnrolledInValidSoft) {
                VoiceIVRFutureCallback futureCallback = new VoiceIVRFutureCallback(context,msisdn);
                postRequest(verifyUserUrl, verifyAndRegistartionPostData ,
                        futureCallback);
                response.sendRedirect(url);
            } else {
                VoiceIVRFutureCallback futureCallback = new VoiceIVRFutureCallback(context,msisdn);
                postRequest(onBoardUserUrl, verifyAndRegistartionPostData ,
                        futureCallback);
                response.sendRedirect(url);
            }
        } catch (IOException e) {
            log.error("Error occurred while redirecting request", e);
        } catch(JSONException je){
            log.error("Error occurred due to JSON Exception", je);
        } catch(SQLException sqle){
            log.error("Error occurred due to SQL Exception", sqle);
        } catch(AuthenticatorException ae){
            log.error("Error occurred due to AuthenticatorException Exception", ae);
        }
    }

    @Override
    protected void processAuthenticationResponse(HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse, AuthenticationContext authenticationContext) throws AuthenticationFailedException {
        log.info("Initiating AuthenticationResponse from Voice Call Authenticator");
        String msisdn = (String) authenticationContext.getProperty(MSISDN);
        String sessionDataKey = httpServletRequest.getParameter("sessionDataKey");
        boolean isUserExists =false;
        try {
            isUserExists= AdminServiceUtil.isUserExists(msisdn);
        } catch (UserStoreException e) {
            log.error("Error occurred due to UserStoreException Exception", e);
        }
        if(!isUserExists){
            String operator = (String) authenticationContext.getProperty(Constants.OPERATOR);
            try {
                new UserProfileManager().createUserProfileLoa2(msisdn, operator, Constants.SCOPE_MNV);
            } catch (UserRegistrationAdminServiceIdentityException e) {
                log.error("Error occurred due to UserRegistrationAdminServiceIdentityException Exception", e);
            } catch (RemoteException e) {
                log.error("Error occurred due to RemoteException", e);
            }
        }

        String responseStatus = null;
        try {
            responseStatus = DBUtils.getAuthFlowStatus(sessionDataKey);
        } catch (AuthenticatorException e) {
            throw new AuthenticationFailedException("USSD Authentication failed while trying to authenticate", e);
        }

        if (responseStatus != null && responseStatus.equalsIgnoreCase("APPROVED")){
            authenticationContext.setProperty(IS_FLOW_COMPLETED , true);
            AuthenticationContextHelper.setSubject(authenticationContext,
                    authenticationContext.getProperty(Constants.MSISDN).toString());
            log.info("FederatedAuthenticator Authentication success");

        } else {
            log.info("Authentication failed. Consent not provided.");
            authenticationContext.setProperty("faileduser", (String) authenticationContext.getProperty("msisdn"));
            throw new AuthenticationFailedException("Authentication Failed");
        }
        
    }

    @Override
    public boolean canHandle(HttpServletRequest httpServletRequest) {
        log.info("Initiating canHandle from Voice Call Authenticator");
        return true;
    }

    @Override
    public String getContextIdentifier(HttpServletRequest httpServletRequest) {
        return null;
    }

    @Override
    public String getName() {
        return Constants.VOICECALL_AUTHENTICATOR_NAME;
    }

    @Override
    public String getFriendlyName() {
        return Constants.VOICECALL_AUTHENTICATOR_FRIENDLY_NAME;
    }



    private boolean verifyIsUserActiveFromValidSoftServer(String url, StringEntity postData) throws IOException, JSONException {
        log.info("Calling VlaidSoft to verify User ");
        HttpClient httpClient = HttpClientBuilder.create().build();
        HttpPost httpPost = new HttpPost(url);
        postData.setContentType(APPLICATION_JSON);
        httpPost.setEntity(postData);
        HttpResponse httpResponse = httpClient.execute(httpPost);
        if (httpResponse.getStatusLine().getStatusCode() != HttpStatus.SC_OK) {
            log.error("ValidSoft server replied with invalid HTTP status [ " + httpResponse.getStatusLine().getStatusCode()
                    + "] ");
            throw new IOException(
                    "Error occurred while Calling ValidSoft server");

        } else {
            String json = EntityUtils.toString(httpResponse.getEntity());
            JSONObject jsonObj = new JSONObject(json);
            ValidSoftJsonHelper jsonHeler = new ValidSoftJsonHelper();
            boolean userStatus = jsonHeler.validateisUserEnrolledJsonRespone(jsonObj);
            log.info("ValidSoft server replied with OK HTTP status with Json response " + json + " Status : " + userStatus);
            return userStatus;
        }


    }

    private void postRequest(String url, StringEntity requestStr, BasicFutureCallback futureCallback)
            throws IOException {

        final HttpPost postRequest = futureCallback.getPostRequest();
        try {
            postRequest.setURI(new URI(url));
        } catch (URISyntaxException ex) {
            log.error("Malformed URL - " + url, ex);
        }

        postRequest.addHeader("accept", APPLICATION_JSON);
        StringEntity input = requestStr;
        input.setContentType(APPLICATION_JSON);
        postRequest.setEntity(input);
        log.info("Posting data  [ " + requestStr + " ] to url [ " + url + " ]");
        Util.sendAsyncRequest(postRequest, futureCallback);
    }




}