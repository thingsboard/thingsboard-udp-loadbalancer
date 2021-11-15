# ThingsBoard UDP Load Balancer

The Load Balancer is designed to support popular CoAP and LwM2M use-cases, where the main priority is to maintain the route table between clients and servers.

## Building from sources

Build scripts require Linux based OS and the following packages to be installed:
 * Ubuntu 16.04+
 * CentOS 7.1+
 * Java 11
 * Maven 3.1.0+
 
### Build

After you've downloaded the code from GitHub, you can build it using Maven: 

```mvn clean install```

### Build artifacts

You can find debian, rpm and windows packages in the target folder:

`application/target`

### Build local docker images and publish

1) Change the repository name in the `msa/pom.xml` file.
2) Execute the following command: 
```mvn clean install -Ddockerfile.skip=false -Dpush-docker-image=true```
