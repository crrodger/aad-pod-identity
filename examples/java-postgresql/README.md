---
title: "Spring Boot + Azure Database for Postgres + AAD Pod Identity"
linkTitle: "Spring Boot + Azure Database for Postgres + AAD Pod Identity"
weight: 2
description: >
  Sample code and instructions demonstrating how you can access Azure Database for PostgreSQL using a user-assigned identity with the aid of AAD Pod Identity from a spring boot application.

---

# Introduction

This is a sample application (very basic) to explore and demonstrate connecting to an Azure Database for PostgreSQL using AAD Pod Identity. It also illustrates two different ways of retrieving a token (via the SDK and also direct to the IMDS HTTP endpoint). There are no tests in the project, it is quite 'rough and ready' and would not be a guide to best practise, but should still be useful as a starting point.

The application was originally written to explore and understand the implication of token lifetimes and what impact that has on database connections. Would an active or open connection continue to function if the token used to originall authenticate has expired? It is written to enable creating a connection and storing that connection and then intermittently using an action that updates the database (with a basic message and timestamp), through REST API endpoints.

There are also some REST endpoints that will execute multiple transactions in a loop. The iteration count is passed as a parameter in the call.

The application can also run database insertion code at startup (configurable in the deployment.yml file). This supports running up a number of pods (also in the deployment.yml i.e. replicas) to test multiple pods retrieving tokens and accessing the database. This demonstrates a stress scenario and multiple accesses to token endpoints.

Token retrieval using multiple scenarios is included to provide a basis for comparing the approach and respective performance.

1. Using the preview Azure SDK for Java [Documentation](https://azuresdkdocs.blob.core.windows.net/$web/java/azure-authentication-msi-token-provider/1.1.0-preview.1/index.html)
2. Using the stable Azure SDK for Java [Documentation](https://docs.microsoft.com/en-us/azure/developer/java/)
3. Using a direct HTTP request [Documentation](https://docs.microsoft.com/en-us/azure/active-directory/managed-identities-azure-resources/how-to-use-vm-token#get-a-token-using-java)

There are two main parts to the application

1. REST endpoint at which 'commands' can be aimed to perform certain tasks. The application is intended to be deployed into Kubernetes (in this case an Azure AKS instance was used) that has AAD Pod Identity already installed. The REST commands available are explained below.
2. Startup code that can be triggered (based on variables in the deployment yaml file - see scripts folder) which helps if you want to test something being run in many pods simultaneously.

# Getting Started

## Prerequisites

The application was written for a specific purpose so the functionality may look a little odd for a sample application. This is due to the intended purpose for which this sample was initially developed being to test token retrieval in different scenarios.

1. Kubernetes (AKS was used for this sample) - the application will only run correctly if deployed into a Kubernetes cluster with AAD Pod Identity installed
2. AAD Pod Identity deployed and configured in the cluster (including creating a Managed Identity either with the cli or in the portal)
3. Docker on the development machine - the image is built locally using a Dockerfile. This helps with making sure the version of Java used is consistent when deployed to the cluster.
4. An Azure Container Registry (ACR) instance that can be access from the developer machine and the Kubernetes cluster. The code is compiled using a 'docker build' command and the pushed to ACR. The Kubernetes cluster will pull the image from that ACR
5. A Managed Identity setup in Azure that is available to the VM Scale Set on which AKS is running
6. An Azure Database for PostgreSQL instance setup and configured to enable Managed Identity connections [see documentation here](https://docs.microsoft.com/en-us/azure/postgresql/howto-connect-with-managed-identity)

### Setup steps

1. Clone the repository to a local folder

2. Update the deployment variables in the deploy-postgres.yml file (see instructions below)

3. Ensure Docker is running locally to run the build

4. Build the Docker image in a terminal located in the source code root folder, using
   
   ```
   docker build -t <acr_service_name>/<repository>:<tag> -f ./Dockerfile .
   ```

5. Push the Docker image to the ACR repository (you may need to login to ACR first)
   
   ```
   docker push <acr_service_name>/<repository>:<tag>
   ```

6. Ensure the deployment file deploy-postgres.yml is configured correctly.
   Important elements are
   
   ```yml
   aadpodidbinding: <selector as per identity and identity binding setup>
   ...
   image <acr_service_name>/<repository>:<tag> 
   ...
   env:
        - name: CLIENT_ID
          value: "<INSERT_CLIENT_ID_HERE>"
        - name: TENANT_ID
          value: "<INSERT_TENANT_ID_HERE>"
        - name: CONNECT_STRING
          value: "<INSERT_CONNECTION_STRING_HERE>"
        - name: DB_USERNAME
          value: "<INSERT_DATABASE_USERNAME_HERE>"
        - name: RUN_STARTUP_CODE
          value: "true"
        - name: ITERATIONS
          value: "5000"
        - name: "TOKEN_REFRESH_COUNT"
          value: "1000"
   ```
   
   The env section shown above determines how and if the startup code is executed
   
   ```textile
   CLIENT_ID is the client ID retreived from creating the Managed Identity that will be used
   TENANT_ID is your subscription tenant id (can be obtained from the Azure Portal)
   CONNECT_STRING the PostgreSQL connection string. It should include a paramer option for the databasename, username and password (%s). The Java code will complete 
   this using the other environment variables defined from the above YAML fragment
   ```
   
   An example connection string - change ""your_database_resource_name""
   
   ```java
   jdbc:postgresql://your_database_resource_name.postgres.database.azure.com:5432/%s?user=%s&password=%s&sslmode=require
   ```
   
   if RUN_STARTUP_CODE is "true" then it will run, otherwise the startup code is not executed
   ITERATIONS determine how many runs to make of the startup code, useful for testing volume requests with many pods (replicas setting)
   TOKEN_REFRESH_COUNT after how many iterations should a token request be submitted (to explore token refresh times)

##### Note:

If you have more than 1 pod deployed (replicas > 1) it will not be practical to connect to the REST endpoints. When replicas is greater than 1, there are multilpe instances of the Springboot app (the number of replicas) and connections to the web endpoint are round robin'd between them. Each instance will require a different password (from the log output) to connect. Because there is only a single IP address assigned to the LoadBalancer service, you will not know which password is required for which instance. The suggestion is either do a volume test (use startup code and replicas >1); or use the REST endpoint. You can use a replicas of 1, have the startup code run, and then connect to the REST endpoints as there will only be a single pod/instance so only a single pod will receive connections.

6. Deploy the app to the AKS cluster
   
   ```
   kubectl apply -f ./scripts/deploy-postgres.yml
   ```

### Using

The startup code option is dealt with in the above setup notes. This section outlines how to use the REST API endpoints.

These are the endpoints available from the application:

```html
/                     - (root url) - will display this information message in the browser
/connect              - create a connection to the PostgreSQL database. It is kept open indefinitely
/save                 - insert a record into the logs table with a message and timestamp (UTC) of the operation
/info                 - display information about the token, connection and last update time (UTC)
/hammersdk?iters=n    - loop through n iterations of retrieving a token using stable Azure Java SDK
/hammersdkp?iters=n.  - loop through n iterations of retrieving a token using  preview 1.10 Azure Java SDK
/hammerhttp?iters=n.  - loop through n iterations of retrieving a token using a direct HTTP connection
```

You have to connect before you can use /save or any of the /hammer... endpoints. This is intentional in that connect information is stored and reused by the other endpoints and was done to be able to test how long a connection remains valid.
