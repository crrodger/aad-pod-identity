# Introduction 
This is a sample application (very basic) to explore and demonstrate connecting to an Azure Database for PostgreSQL through AAD Pod Identity. It also illustrates two different ways of retrieving a token. There are no tests in the original version, it is quite rough and ready but should still be useful as a starting point.

The applicaiton was originally written to explore and understand the implication of token lifetimes being 24 Hours and what impact that has on database connections. Would an active or open connection continue to function if the token used to originall authenticate has expired. It is written to enable creating a connection and storing that connection and then intermittently using an action that updates the database (with a basic message and timestamp). 

It also has the capability of running database insertion code at startup (configurable in the delpoyment.yml file). This supports running up a number of pods (also in the deployment.yml i.e. replicas) to test multiple pods retrieving tokens and accessing the database.


Token retrieval using multiple scenarios has also been included to provide a basis for comparing the approach and respective performance.
1. Using the preview Azure SDK for Java [Documentation](https://azure.github.io/azure-sdk-for-java/authentication.html)
2. Using the stable Azure SDK for Java
3. Using a direct HTTP request [Documentation](https://docs.microsoft.com/en-us/azure/active-directory/managed-identities-azure-resources/how-to-use-vm-token#get-a-token-using-java)

There are two main aspects to the application

A. A REST endpoint at which 'commands' can be aimed to perform certain tasks. The application is intended to be deployed into Kubernetes (in this case an Azure AKS instance was used) that has AAD Pod Identity already installed. 
B. Startup code that can be triggered (based on variables in the deployment file) which helps if you want to test something being run in many pods simultaneously.


# Getting Started

## Prerequisites

The application was written for a specific initial environment so the requirements for it may look a little odd for a sample application. This is due to the target environment a production app was expected to run in.

1. Kubernetes (AKS was used for this sample) - the application will only run correctly if deployed into a Kubernetes cluster with AAD Pod Identity deployed
2. AAD Pod Identity deployed and configured in the cluster
3. Docker on the development machine - the image is built locally using a Dockerfile. This helps with making sure the version of Java used is consistent when deployed to the cluster.
4. An Azure Container Registry (ACR) instance that can be access from the developer machine and the Kubernetes cluster. The code is compiled using a 'docker build' command and the pushed to ACR. The Kubernetes cluster will pull the image from that ACR

Setup steps

1. Clone the repository to a local folder
2. Update the client_id value in the source code. It is better to use this as a property or environment variable in production code but for this trivial sample it is hard coded. This client_id will not work on other deployments as it is a Managed Identity in the subscription used to show AAD Pod Identity
3. Ensure Docker is running locally to run the build
4. Build the Docker image using
```
docker build -t <acr_service_name>/<repository>:<tag> -f ./Dockerfile .
```
4. Push the Docker image to the ACR repository (you may need to login to ACR first)
```
docker push <acr_service_name>/<repository>:<tag>
```
5. Ensure the deployment file deploy-postgres.yml is configured correctly.
Important elements are
```
aadpodidbinding: <selector as per identity and identity binding setup>
...
image <acr_service_name>/<repository>:<tag> 
...
env:
          - name: CLIENT_ID
            value: ""
          -name: TENANT_ID
            value: ""
          - name: RUN_STARTUP_CODE
            value: "true"
          - name: ITERATIONS
            value: "5000"
          - name: "TOKEN_REFRESH_COUNT"
            value: "1000"
```
The env section determines how the startup code is executed
if RUN_STARTUP_CODE is "true" then it will run otherwise the startup code is not executed
ITERATIONS determine how many runs to make of the startup code, useful for testing volumes requests
TOKEN_REFRESH_COUNT after how many iterations should a token request be submitted

