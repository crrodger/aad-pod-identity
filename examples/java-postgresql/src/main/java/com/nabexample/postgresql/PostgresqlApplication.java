package com.nabexample.postgresql;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;

import com.azure.core.credential.AccessToken;
import com.azure.core.credential.TokenRequestContext;
import com.azure.identity.DefaultAzureCredential;
import com.azure.identity.DefaultAzureCredentialBuilder;
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

All work is done in the RequestController class. It responds to certain HTTP URLs to do basic tasks
connect - to the database using a token
save - a record that contains a message and a timestamp
info - retrieve information about the connection
hammersdk, hammersdkp and hammersdkhtml - loop through repeated token requests to explore token throughput rates
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


    private Connection ConnectToDatabase(Mono<AccessToken> token) throws Exception{
        String strToken = token.block().getToken();
        String connectStr = String.format("jdbc:postgresql://aks-nab-pgsql.postgres.database.azure.com:5432/demodb?user=%s&password=%s&sslmode=require", "demouser@aks-nab-pgsql", strToken);
         conn = DriverManager.getConnection(connectStr);
         return conn;       
    }

	@EventListener(ApplicationReadyEvent.class)
	public void doSomethingAfterStartup() {
        // Config variables are intended to be provided by the deployment.yml file
        String should_run = System.getenv("RUN_STARTUP_CODE"); //Should this function execute
        String str_iters = System.getenv("ITERATIONS"); //How many records to insert
        String str_host = System.getenv("HOSTNAME"); //Used to make each message from each pod identifiable
        String str_refresh_interval = System.getenv("TOKEN_REFRESH_COUNT"); //How often to 'refresh' the token, or rather get a new one
        String str_client_id = System.getenv("CLIENT_ID");
        double iterations = 500; //default value
        double refresh_interval = 50; //default refresh if not provided in yml/environment
        Timestamp sqlTS; //For insertion into the log table

        if (should_run == null || should_run.compareTo("false") == 0) {
            if (should_run == null) {
                System.out.println("should_run is null and startup code will not run");
            } else {
                System.out.println("should_run has value of " + should_run);
            }
            return;
        }
        System.out.println("should_run has value of " + should_run);
        if (str_iters != null) {
            iterations = Double.parseDouble(str_iters);
        }
        if (str_refresh_interval != null) {
            refresh_interval = Double.parseDouble(str_refresh_interval);
        }
		
		System.out.println("Startup code - Hammer(using SDK) with " + iterations + " iterations");
		
		long startTime = 0;
        long endTime = 0;

        System.out.println("Will perform " + iterations + " loops");
        if (iterations > 0) {
            ManagedIdentityCredential mic = new ManagedIdentityCredentialBuilder()
                .clientId(str_client_id)
                .maxRetry(5)
                .retryTimeout(i -> Duration.ofSeconds((long) Math.pow(2, i.getSeconds() - 1)))
                .build();
            // micb.tenantId("72398b55-518d-42fc-a448-a904194785b8");

            TokenRequestContext trq = new TokenRequestContext();
            trq.addScopes("https://ossrdbms-aad.database.windows.net");

            Mono<AccessToken> token = null;
            //Do first token to ensure on the machine before looping, to remove first retrieval delay
            try {
                token = mic.getToken(trq);
                System.out.println("Have token, now connecting to database");
                this.conn = ConnectToDatabase(token);
                System.out.println("Connnected...");
            } catch (Exception ex) {
                System.out.println("IOException getting first token " + ex.getMessage());
                return ;
            }

            //Prepare statement once and then reuse with parameters
            try {
                this.stmnt = this.conn.prepareStatement("INSERT INTO logs VALUES(?, ?)");
            } catch (Exception ex) {
                System.out.println("Exception in preparing statement " + ex.getMessage());
            }

            startTime = System.currentTimeMillis();
            for (int i=0; i<iterations; i++) {
                try {
                    String strMessage = "An entry from  " + str_host + " : #" + i;
                    
                    sqlTS = new Timestamp(System.currentTimeMillis());
                    stmnt.setObject(1, strMessage);
                    stmnt.setObject(2, sqlTS, java.sql.Types.TIMESTAMP);
                    
                    stmnt.executeUpdate();
                    
                    if (i % refresh_interval == 0) {
                        token = mic.getToken(trq);
                        this.conn = ConnectToDatabase(token);
						System.out.println("Doing a fresh connection... Loop " + i);
					}
                } catch (Exception ex) {
                    System.out.println("Exception processing statement and token token on iteration " + i + " with message" + ex.getMessage());
                    return;
                }
            }
            endTime = System.currentTimeMillis();

            long elapsedTime = endTime - startTime;
            double rps = (double)iterations / ((double)elapsedTime/1000);

            System.out.println("Performed " + iterations  + " loops");
            System.out.println("In " + (double)elapsedTime/1000.0 + " seconds");
            System.out.println("providing a rate of " + rps + " requests per second");
            

        }
        return;
	}

}
