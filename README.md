# ThingsBoard UDP Load Balancer

The Load Balancer is designed to support popular CoAP and LwM2M use-cases, where the main priority is to maintain the route table between clients and servers.

## Compilation

To build this image, clone this repository, and run maven:

```
git clone git@github.com:thingsboard/thingsboard-udp-loadbalancer
cd thingsboard-udp-loadbalancer
docker run -it --rm --name tb-udp-lb -v "$(pwd)":/usr/src/mymaven -w /usr/src/mymaven maven:3.8.3-jdk-11 mvn clean package
```

then, using the compiled `.deb`, build and push the docker image.

```
cd msa/udp-lb-docker/target
docker build . -t mynamespace/tb-udp-lb:v0.1.0
```
