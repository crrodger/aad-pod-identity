package com.pgexample.postgresql;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Duration;

import com.azure.core.credential.AccessToken;
import com.azure.core.credential.TokenRequestContext;
import com.azure.identity.ManagedIdentityCredential;
import com.azure.identity.ManagedIdentityCredentialBuilder;

import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

import reactor.core.publisher.Mono;

/*
A basic application to test and show approaches to connecting to an Azure Database for PostgreSQL using AAD Pod Identity. It was developed to test if a connection could be kept open to a database
beyond the timeout period of the AAD token (which is 24 hours).

It also illustrates a couple of different approaches to retrieving tokens from AAD Pod Identity (an HTTP approach and an Azure Java SDK approach, using the stable and preview SDK)

All work is done in the RequestController class. It responds to certain HTTP URLs to do basic tasks. Accessing the root url '/' returns usage instructions

*/

@SpringBootApplication
public class PostgresqlApplication {
    Connection conn = null;
    PreparedStatement stmnt = null;
	org.slf4j.Logger logger = LoggerFactory.getLogger(PostgresqlApplication.class);

	public static void main(String[] args) {
		System.out.println("Starting");
		SpringApplication.run(PostgresqlApplication.class, args);
	}


    private Connection connectToDatabase(Mono<AccessToken> token, String dbConnectString, String dbName, String dbUsername) throws Exception{
        logger.info("Retrieving token and connecting to the database");
        String strToken = token.block().getToken();
        String connectStr = String.format(dbConnectString, dbName, dbUsername, strToken);
         conn = DriverManager.getConnection(connectStr);
         return conn;       
    }

	@EventListener(ApplicationReadyEvent.class)
	public void doSomethingAfterStartup() {
        // Config variables are intended to be provided by the deployment.yml file
        String shouldRun = System.getenv("RUN_STARTUP_CODE"); //Should this function execute
        String strIters = System.getenv("ITERATIONS"); //How many records to insert
        String strHost = System.getenv("HOSTNAME"); //Used to make each message from each pod identifiable
        String strRefreshInterval = System.getenv("TOKEN_REFRESH_COUNT"); //How often to 'refresh' the token, or rather get a new one
        String strClientId = System.getenv("CLIENT_ID");
        String strTenantId = System.getenv("TENANT_ID");
        String dbConnectString = System.getenv("CONNECT_STRING"); //Connect string with substitution parameter embedded ...?user=%s...
        String dbUsername = System.getenv("DB_USERNAME"); //Database username linked with Managed Identity to use to connect
        String dbName = System.getenv("DB_DATABASE");
        String dbTable = System.getenv("DB_TABLE");
        double iterations = 500; //default value
        double refreshInterval = 50; //default refresh if not provided in yml/environment
        Timestamp sqlTS; //For insertion into the log table

        logger.info("Pod HOSTNAME {}", strHost);
        logger.info("Client ID {}", strClientId);
        logger.info("Tenant ID {}", strTenantId);
        logger.info("Database connection string {}", dbConnectString);
        logger.info("Database username {}", dbUsername);
        logger.info("Database name to insert records into {}", dbName);
        logger.info("Database table to insert records into {}", dbTable);

        if (shouldRun == null || shouldRun.compareTo("false") == 0) {
            if (shouldRun == null) {
                logger.info("should_run is null and startup code will not run");
            } else {
                logger.info("should_run has value of {}", shouldRun);
            }
            return;
        }
        logger.info("should_run has value of {}", shouldRun);
        if (strIters != null) {
            iterations = Double.parseDouble(strIters);
        }
        if (strRefreshInterval != null) {
            refreshInterval = Double.parseDouble(strRefreshInterval);
        }
		
        logger.info("Startup code - Hammer(using SDK) with {} iterations", iterations);
        
		long startTime = 0;
        long endTime = 0;

        logger.info("Will perform {} loops", iterations);
        if (iterations > 0) {
            ManagedIdentityCredential mic = new ManagedIdentityCredentialBuilder()
                .clientId(strClientId)
                .maxRetry(5)
                .retryTimeout(i -> Duration.ofSeconds((long) Math.pow(2, i.getSeconds() - 1)))
                .build();
            
            TokenRequestContext trq = new TokenRequestContext();
            trq.addScopes("https://ossrdbms-aad.database.windows.net");

            Mono<AccessToken> token = null;
            //Do first token to ensure on the machine before looping, to remove first retrieval delay
            try {
                token = mic.getToken(trq);
                logger.info("Have token, now connecting to database");
                this.conn = connectToDatabase(token, dbConnectString, dbName, dbUsername);
                logger.info("Connnected...");
            } catch (Exception ex) {
                logger.error("IOException getting first token {}", ex.getMessage());
                return ;
            }

            //Prepare statement once and then reuse with parameters
            try {
                this.stmnt = this.conn.prepareStatement("INSERT INTO " + dbTable + " VALUES(?, ?)");
            } catch (Exception ex) {
                logger.error("Exception in preparing statement {}", ex.getMessage());
            }

            startTime = System.currentTimeMillis();
            for (int i=0; i<iterations; i++) {
                try {
                    String strMessage = "An entry from  " + strHost + " : #" + i;
                    
                    sqlTS = new Timestamp(System.currentTimeMillis());
                    stmnt.setObject(1, strMessage);
                    stmnt.setObject(2, sqlTS, java.sql.Types.TIMESTAMP);
                    
                    stmnt.executeUpdate();
                    
                    if (i % refreshInterval == 0) {
                        token = mic.getToken(trq);
                        this.conn = connectToDatabase(token, dbConnectString, dbName, dbUsername);
						logger.info("Doing a fresh connection... Loop {}", i);
					}
                } catch (Exception ex) {
                    logger.error("Exception processing statement and token token on iteration {} with message {}", i, ex.getMessage());
                    return;
                }
            }
            endTime = System.currentTimeMillis();

            long elapsedTime = endTime - startTime;
            double rps = (double)iterations / ((double)elapsedTime/1000);

            logger.info("Performed {} loops",  iterations);
            logger.info("In {} seconds", (double)elapsedTime/1000.0);
            logger.info("providing a rate of {} requests per second", rps);
            

        }
	}
}
