package com.pgexample.postgresql.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.HttpURLConnection;
import java.net.URL;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;

import com.microsoft.azure.msiAuthTokenProvider.*;
import com.azure.core.credential.AccessToken;
import com.azure.core.credential.TokenRequestContext;
import com.azure.identity.DefaultAzureCredential;
import com.azure.identity.DefaultAzureCredentialBuilder;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import reactor.core.publisher.Mono;

import java.io.*;
import com.fasterxml.jackson.core.*;

/*
Basic REST controller to respond to URL events and drive the application using that.
See the function getUsage() in this file for URLS available
*/
@RestController
public class RequestController {

    private static Logger logger = LoggerFactory.getLogger(RequestController.class);

    private String token = "";
    private MSIToken msiToken = null;
    Connection conn = null;
    PreparedStatement stmnt = null;
    private Timestamp connectTime = null;
    private Timestamp lastSaveTime = null;

    /*
    Default index method, not doing anything
    */
    @RequestMapping("/")
    public String index() {
        return "The index location does not do anything (other than return this message), try /help to see usage guidance";
    }

    /*
    Retrieve a token using the Azure Java SDK authentication library https://azure.github.io/azure-sdk-for-java/authentication.html
    Use tht token to create a connection to the database. The connection is stored in the controller (not created each time needed as this basic example
    is to test how long that connection remains valid for)
    */
    @RequestMapping("/connect")
    @ResponseStatus(HttpStatus.CREATED)
    public String connectToDatabase() {
        String strClientId = System.getenv("CLIENT_ID");
        String dbConnectString = System.getenv("CONNECT_STRING"); //Connect string with substitution parameter embedded ...?user=%s...
        String dbUsername = System.getenv("DB_USERNAME"); //Database username linked with Managed Identity to use to connect
        String dbName = System.getenv("DB_DATABASE");
        StringBuilder sbRes = new StringBuilder();
        sbRes.append("Token creation log <br>");

        MSICredentials credsProvider = MSICredentials.getMSICredentials();
        credsProvider.updateClientId(strClientId);
        MSIToken token = null;

        if (this.msiToken != null) {
            sbRes.append("Token already initialised, will re-create/re-retrieve<br>");
        } else {
            sbRes.append("Token has not been initialised previously, creating...<br>");
        }

        try {
            sbRes.append("Getting token <br>");
            token = credsProvider.getToken("https://ossrdbms-aad.database.windows.net");
            this.token = token.accessToken();
            this.msiToken = token;
            sbRes.append("Token value  " + this.token.substring(0, 5) + "..." + this.token.substring(this.token.length() - 6, this.token.length()) + " <br>");
            
            logger.info("Connection string from env variable {}", dbConnectString);
            logger.info("Database username from env variable {}", dbUsername);
            logger.info("Database name from env variable {}", dbName);
            logger.info("Token retrieved {}", this.token);

            String connectStr = String.format(dbConnectString, dbName, dbUsername, this.token);
            String redactConnectStr = String.format(dbConnectString, dbName, dbUsername, this.token.substring(0, 5) + "..." + this.token.substring(this.token.length() - 6, this.token.length()));
            logger.info("Completed connection string {}", connectStr);
            sbRes.append("Connection string : " + redactConnectStr + "<br>");
            
                try {
                    this.conn = DriverManager.getConnection(connectStr);
                } catch (SQLException sqlex) {
                    sbRes.append("SQL Exception error : <br>" +sqlex  + "<br>");
                } finally {
                    if (stmnt != null)
                        stmnt.close();
                }
            } catch (Exception ex) {
                sbRes.append("Exception : <br>" + ex.getMessage() + "<br>");
            } 

            this.connectTime = new Timestamp(System.currentTimeMillis());
            sbRes.append("<br>");
            return sbRes.toString();
    }

    @GetMapping("/hammersdk")
    @ResponseBody
    public String maxRate(@RequestParam(name = "iters") String strIters) {
        logger.info("Hammer(SDK) with {} iterations as parameter", strIters);
        String strClientId = System.getenv("CLIENT_ID");
        String strTenantId = System.getenv("TENANT_ID");
        String dbTable = System.getenv("DB_TABLE");
        StringBuilder sbRes = new StringBuilder();
        String strInsert = "";
        long startTime = 0;
        long endTime = 0;

        double iterations = Double.parseDouble(strIters);

        sbRes.append("Will perform " + iterations + " loops, no feedback until completed<br>");
        if (iterations > 0) {
            DefaultAzureCredentialBuilder dacb = new DefaultAzureCredentialBuilder();
            dacb.managedIdentityClientId(strClientId);
            dacb.tenantId(strTenantId);
            DefaultAzureCredential dac = dacb.build();

            TokenRequestContext trq = new TokenRequestContext();
            trq.addScopes("https://ossrdbms-aad.database.windows.net");

            Mono<AccessToken> token = null;

            //Do first token to ensure on the machine before looping, to remove first retrieval delay
            //The token is not actually used here, this is to test simply retrieving it and what the performance of that is like. The token is used only in the connectToDatabase method
            try {
                token = dac.getToken(trq);
            } catch (Exception ex) {
                sbRes.append("IOException getting first token " + ex.getMessage() + "<br>");
                return sbRes.toString();
            }

            startTime = System.currentTimeMillis();
            for (int i=0; i<iterations; i++) {
                try {
                    token = dac.getToken(trq);
                    //The token is not actually used here, this is to test simply retrieving it and what the performance of that is like. The token is used only in the connectToDatabase method
                    strInsert = "Insert record " + i + " of " + iterations;
                    executeDatabaseInsert(strInsert, dbTable, sbRes);
                } catch (Exception ex) {
                    sbRes.append("Exception getting token on iteration " + i + " with message" + ex.getMessage() + "<br>");
                    return sbRes.toString();
                }
            }
            endTime = System.currentTimeMillis();

            long elapsedTime = endTime - startTime;
            double rps = (double)iterations / ((double)elapsedTime/1000);

            sbRes.append("Performed " + iterations  + " loops <br>");
            sbRes.append("In " + (double)elapsedTime/1000.0 + " seconds <br>");
            sbRes.append("providing a rate of " + rps + " requests per second <br>");
            sbRes.append("<br>");

        }
        return sbRes.toString();
    }

    /*
    A routine accessed with url ../hammer
    that loops the number of times passed as the iters parameter (/hammer?iters=nnnn)
    to test how many token requests can be processed per second

    This function uses the preview version of the authentication library https://azure.github.io/azure-sdk-for-java/authentication.html
    */
    @GetMapping("/hammersdkp")
    @ResponseBody
    public String maxRatep(@RequestParam(name = "iters") String strIters) {
        logger.info("Hammer(SDK Preview) with {} iterations as parameter", strIters);
        String strClientId = System.getenv("CLIENT_ID");
        String dbTable = System.getenv("DB_TABLE");
        StringBuilder sbRes = new StringBuilder();
        String strInsert = "";
        long startTime = 0;
        long endTime = 0;

        double iterations = Double.parseDouble(strIters);

        sbRes.append("Will perform " + iterations + " loops, no feedback until completed<br>");
        if (iterations > 0) {
            MSICredentials credsProvider = MSICredentials.getMSICredentials();
            credsProvider.updateClientId(strClientId);
            MSIToken token = null;

            //Do first token to ensure on the machine before looping, to remove first retrieval delay
            //The token is not actually used here, this is to test simply retrieving it and what the performance of that is like. The token is used only in the connectToDatabase method
            try {
                token = credsProvider.getToken("https://ossrdbms-aad.database.windows.net");
            } catch (Exception ex) {
                sbRes.append("Exception getting first token " + ex.getMessage() + "<br>");
                return sbRes.toString();
            }

            startTime = System.currentTimeMillis();
            for (int i=0; i<iterations; i++) {
                try {
                    //The token is not actually used here, this is to test simply retrieving it and what the performance of that is like. The token is used only in the connectToDatabase method
                    token = credsProvider.getToken("https://ossrdbms-aad.database.windows.net");
                    strInsert = "HammerSDK Preview - Insert record " + i + " of " + iterations;
                    executeDatabaseInsert(strInsert, dbTable, sbRes);
                } catch (Exception ex) {
                    sbRes.append("Exception getting token on iteration " + i + " with message" + ex.getMessage() + "<br>");
                    return sbRes.toString();
                }
            }
            endTime = System.currentTimeMillis();

            long elapsedTime = endTime - startTime;
            double rps = (double)iterations / ((double)elapsedTime/1000);

            sbRes.append("Performed " + iterations  + " loops <br>");
            sbRes.append("In " + (double)elapsedTime/1000.0 + " seconds <br>");
            sbRes.append("providing a rate of " + rps + " requests per second <br>");
            sbRes.append("<br>");

        }
        return sbRes.toString();
    }

    /*
    Function to retreive a token using an HTTP request (bypassing the Java SDK routines to explore difference in performance)
    Token is returned to caller as a String
    */
    private String getTokenWithHTTP() throws Exception {
        StringBuilder sbRes = new StringBuilder();
        URL msiEndpoint = new URL("http://169.254.169.254/metadata/identity/oauth2/token?api-version=2018-02-01&resource=https://ossrdbms-aad.database.windows.net/");
        HttpURLConnection con = (HttpURLConnection) msiEndpoint.openConnection();
        con.setRequestMethod("GET");
        con.setRequestProperty("Metadata", "true");
 
        if (con.getResponseCode()!=200) {
            throw new Exception("Error calling managed identity token endpoint.");
        }
 
        InputStream responseStream = con.getInputStream();
 
        JsonFactory factory = new JsonFactory();
        JsonParser parser = factory.createParser(responseStream);
 
        while(!parser.isClosed()){
            JsonToken jsonToken = parser.nextToken();
 
            if(JsonToken.FIELD_NAME.equals(jsonToken)){
                String fieldName = parser.getCurrentName();
                jsonToken = parser.nextToken();
 
                if("access_token".equals(fieldName)){
                    String accesstoken = parser.getValueAsString();
                    sbRes.append(accesstoken);
                }
            }
        }
        return sbRes.toString();
    }

    /*
    Variation of hammer function that uses HTTP to retrieve tokens rather than the Azure Java SDK authentication library
    Exploring difference in performance between the two approaches.
    */
    @GetMapping("/hammerhttp")
    @ResponseBody
    public String maxRateTwo(@RequestParam(name = "iters") String strIters) {
        logger.info("Hammerhttp with {} iterations as parameter",  strIters);
        String dbTable = System.getenv("DB_TABLE");
        StringBuilder sbRes = new StringBuilder();
        String strInsert = "";
        long startTime = 0;
        long endTime = 0;

        double iterations = Double.parseDouble(strIters);

        sbRes.append("Will perform " + iterations + " loops, no feedback until completed<br>");
        if (iterations > 0) {
            
            //Do first token to ensure on the machine before looping, to remove first retrieval delay
            try {
                token = getTokenWithHTTP();
                sbRes.append("Token is " + token);
            } catch (Exception ex) {
                sbRes.append("Exception getting first token " + ex.getMessage() + "<br>");
                return sbRes.toString();
            }

            startTime = System.currentTimeMillis();
            for (int i=0; i<iterations; i++) {
                try {
                    token = getTokenWithHTTP();
                    strInsert = "Hammer token with HTTP - Insert record " + i + " of " + iterations;
                    executeDatabaseInsert(strInsert, dbTable, sbRes);
                } catch (Exception ex) {
                    sbRes.append("Exception getting token on iteration " + i + " with message" + ex.getMessage() + "<br>");
                    return sbRes.toString();
                }
            }
            endTime = System.currentTimeMillis();

            long elapsedTime = endTime - startTime;
            double rps = (double)iterations / ((double)elapsedTime/1000);

            sbRes.append("Performed " + iterations  + " loops <br>");
            sbRes.append("In " + (double)elapsedTime/1000.0 + " seconds <br>");
            sbRes.append("providing a rate of " + rps + " requests per second <br>");
            sbRes.append("<br>");

        }
        return sbRes.toString();
    }

public int executeDatabaseInsert(String message, String dbTable, StringBuilder sbResMsg) {
    Timestamp sqlTS = null;
    int stmntRet = -1;

    if (this.conn != null) {
        try {
            this.stmnt = this.conn.prepareStatement("INSERT INTO " + dbTable + " VALUES(?, ?)");
            sqlTS = new Timestamp(System.currentTimeMillis());
            stmnt.setObject(1, message, java.sql.Types.CHAR);
            stmnt.setObject(2, sqlTS, java.sql.Types.TIMESTAMP);
            stmntRet = stmnt.executeUpdate();
            this.lastSaveTime = sqlTS;
        } catch (SQLException sqlex) {
            logger.error("SQL Exception error {}", sqlex);
         } 
    } else {
        sbResMsg.append("Database is not connected yet, use /connect to initialise the connection <br>");
    }

    return stmntRet;
}

    /*
    Save an arbitrary record to the database that includes a timestamp. 
    Used to check if the connection will remain open for long periods.
    */
    @RequestMapping("/save")
    @ResponseStatus(HttpStatus.CREATED)
    public String createRecord() {
        String dbTable = System.getenv("DB_TABLE");
        StringBuilder sbRes = new StringBuilder();
        int stmntRet = -1;

        sbRes.append("<br>");
        logger.info("Saving single record to logs table");

        stmntRet = executeDatabaseInsert("Single insert from save endpoint", dbTable, sbRes);

        if (stmntRet == 1) {
            sbRes.append("Added record <br>");
            logger.info("Added single record with timestamp");
        } else {
            sbRes.append("Something went wrong adding the record, statement returned " + stmntRet  + " perhaps the connection is either not active or has broken <br>");
            logger.error("Could not insert single record, return from call was {}", stmntRet);
        }

        return sbRes.toString();
    }

/*
A kind of 'help call, when using the root url return the options that can be called'
*/
    @GetMapping("/")
    public String getUsage() {
        StringBuilder sbRes = new StringBuilder();
        sbRes.append("Usage:<br>");
        sbRes.append("===========<br>");
        sbRes.append("<br>");
        sbRes.append("/                                      - (root url) - this information message<br>");
        sbRes.append("/connect                        - create a connection to the PostgreSQL database. It is kept open indefinitely<br>");
        sbRes.append("/save                              - insert a record into the logs table with a message and timestamp (UTC) of the operation<br>");
        sbRes.append("/info                               - display information about the token, connection and last update time (UTC)<br>");
        sbRes.append("/hammersdk?iters=n          - loop through n iterations of retrieving a token using stable Azure Java SDK ()<br>");
        sbRes.append("/hammersdkp?iters=n        - loop through n iterations of retrieving a token using  preview 1.10 Azure Java SDK ()<br>");
        sbRes.append("/hammerhttp?iters=n    - loop through n iterations of retrieving a token using HTTP connection<br>");
        sbRes.append("<br>");
        sbRes.append("<br>");
        return sbRes.toString();
    }

    /*
    Return information about current connection state and last time a record was inserted
    */
    @GetMapping("/info")
    public String getInfo() {
        StringBuilder sbRes = new StringBuilder();
        sbRes.append("Information from the connection<br>");
        sbRes.append("=========================<br>");
        sbRes.append("Connect time : " + this.connectTime);
        sbRes.append("<br>");
        sbRes.append("Last Update Time: " + this.lastSaveTime);
        sbRes.append("<br>");

        if (this.conn == null) {
            sbRes.append("Connection is not initialised, use /connect to intialise it <br>");
        } else {
            sbRes.append("Connection is initialised <br>");
            try {
                if (this.conn.isValid(10)) {
                    sbRes.append("Connection is valid <br>");
                } else {
                    sbRes.append("Connection not valid <br>");
                }

                if (this.conn.isClosed()) {
                    sbRes.append("Connection is closed <br>");
                } else {
                    sbRes.append("Connection is open <br>");
                }
            } catch (SQLException sqlex) {
                sbRes.append("SQL Exception checking if connection is valid <br>" + sqlex.getMessage() + "<br>");
            }
                
        }

        if (this.msiToken != null) 
        {
            sbRes.append("Token expires on: " + this.msiToken.expiresOn().toString());
            sbRes.append("<br>");

            if (this.msiToken.isExpired()) {
                sbRes.append("Token isExpired?: True");
            } else {
                sbRes.append("Token isExpired?: False");
            }
            sbRes.append("<br>");

        } else {
            sbRes.append("Token has not been initialised yet, use /connect");
            sbRes.append("<br>");
        }
        
        sbRes.append("<br>");
        return sbRes.toString();
    }
}
