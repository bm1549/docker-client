package io.paradoxical;

import com.google.common.base.Splitter;
import com.spotify.docker.client.DefaultDockerClient;
import com.spotify.docker.client.DockerCertificateException;
import com.spotify.docker.client.DockerCertificates;
import com.spotify.docker.client.DockerClient;
import com.spotify.docker.client.DockerException;
import com.spotify.docker.client.LogMessage;
import com.spotify.docker.client.LogStream;
import com.spotify.docker.client.messages.ContainerConfig;
import com.spotify.docker.client.messages.ContainerCreation;
import com.spotify.docker.client.messages.ContainerInfo;
import com.spotify.docker.client.messages.HostConfig;
import com.spotify.docker.client.messages.PortBinding;
import org.apache.commons.lang.StringUtils;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static com.spotify.docker.client.DockerClient.AttachParameter.LOGS;
import static com.spotify.docker.client.DockerClient.AttachParameter.STDERR;
import static com.spotify.docker.client.DockerClient.AttachParameter.STDOUT;
import static com.spotify.docker.client.DockerClient.AttachParameter.STREAM;
import static com.spotify.docker.client.DockerClient.LogsParam.follow;
import static com.spotify.docker.client.DockerClient.LogsParam.stdout;

public class DockerCreator {
    private static final Random random = new Random();

    public static Container build(DockerClientConfig config) throws InterruptedException, DockerException, DockerCertificateException {
        return new DockerCreator().create(config);
    }

    public Container create(DockerClientConfig config) throws DockerCertificateException, DockerException, InterruptedException {

        Map<String, List<PortBinding>> portBindings = new HashMap<>();

        for (Integer port : config.getTransientPorts()) {
            portBindings.put(port.toString(), Collections.singletonList(PortBinding.of("0.0.0.0", random.nextInt(30000) + 15000)));
        }

        for (MappedPort mappedPort : config.getMappedPorts()) {
            portBindings.put(mappedPort.getHostPort().toString(),
                             Collections.singletonList(PortBinding.of("0.0.0.0", mappedPort.getContainerPort().toString())));
        }

        HostConfig hostConfig = HostConfig.builder()
                                          .portBindings(portBindings)
                                          .build();

        ContainerConfig.Builder configBuilder =
                ContainerConfig.builder()
                               .hostConfig(hostConfig)
                               .image(config.getImageName())
                               .env(getEnvVars(config.getEnvVars()))
                               .networkDisabled(false)
                               .exposedPorts(getPorts(config.getTransientPorts()));

        if (config.getArguments() != null) {
            configBuilder.cmd(Splitter.on(' ').splitToList(config.getArguments()));
        }

        addCustomConfigs(configBuilder);

        final ContainerConfig container = configBuilder.build();

        final DockerClient client = createDockerClient(config);

        try {
            if (config.isPullAlways()) {
                client.pull(configBuilder.image());
            }
            else {
                client.inspectImage(configBuilder.image());
            }
        }
        catch (Exception e) {
            client.pull(configBuilder.image());
        }

        final ContainerCreation createdContainer = client.createContainer(container);

        client.startContainer(createdContainer.id());

        if (!StringUtils.isEmpty(config.getWaitForLogLine())) {
            waitForLogInContainer(createdContainer, client, config.getWaitForLogLine());
        }

        final ContainerInfo containerInfo = client.inspectContainer(createdContainer.id());

        Map<Integer, Integer> targetPortToHostPortLookup = new HashMap<>();

        for (final Integer port : config.getTransientPorts()) {
            targetPortToHostPortLookup.put(
                    port,
                    Integer.parseInt(containerInfo.networkSettings()
                                                  .ports()
                                                  .get(port + "/tcp")
                                                  .get(0)
                                                  .hostPort())
            );
        }

        return new Container(containerInfo, targetPortToHostPortLookup, client.getHost(), client);
    }

    private List<String> getEnvVars(final List<EnvironmentVar> envVars) {
        List<String> vars = new ArrayList<>();

        for (final EnvironmentVar envVar : envVars) {
            vars.add(envVar.getEnvVarName() + "=" + envVar.getEnvVarValue());
        }

        return vars;
    }

    private String[] getPorts(final List<Integer> ports) {
        Set<String> portsSet = new HashSet<>();
        for (final Integer port : ports) {
            portsSet.add(port.toString());
        }

        return portsSet.toArray(new String[]{});
    }

    protected void addCustomConfigs(final ContainerConfig.Builder configBuilder) {
        // extension point
    }

    protected void waitForLogInContainer(final ContainerCreation createdContainer, final DockerClient client, final String waitForLog)
            throws DockerException, InterruptedException {

        String log = "";
        LogStream logs = client.attachContainer(createdContainer.id(), LOGS, STREAM, STDOUT, STDERR);
        do {
            if(!logs.hasNext()){
                Thread.sleep(10);

                continue;
            }

            LogMessage logMessage = logs.next();
            ByteBuffer buffer = logMessage.content();
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            log += new String(bytes);
        } while (!log.contains(waitForLog));
    }

    protected DockerClient createDockerClient(DockerClientConfig config) {
        if (isUnix() || System.getenv("DOCKER_HOST") != null) {
            try {
                return DefaultDockerClient.fromEnv().build();
            }
            catch (DockerCertificateException e) {
                System.err.println(e.getMessage());
            }
        }

        DockerCertificates dockerCertificates = null;
        try {
            String userHome = System.getProperty("user.home");
            dockerCertificates = new DockerCertificates(Paths.get(userHome, ".docker/machine/certs"));
        }
        catch (DockerCertificateException e) {
            System.err.println(e.getMessage());
        }

        final String dockerMachineUrl = config.getDockerMachineUrl() == null ? DockerClientConfig.DOCKER_MACHINE_SERVICE_URL : config.getDockerMachineUrl();

        return DefaultDockerClient.builder()
                                  .uri(URI.create(dockerMachineUrl))
                                  .dockerCertificates(dockerCertificates)
                                  .build();
    }

    protected boolean isUnix() {
        String os = System.getProperty("os.name").toLowerCase();
        return os.contains("nix") || os.contains("nux") || os.contains("aix");
    }
}
