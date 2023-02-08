package com.example.packetlogger.config;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.ConstructorBinding;

import java.util.List;

public class BaseProperties {

    @Getter
    @ConstructorBinding
    @RequiredArgsConstructor
    @ConfigurationProperties(prefix = "packet-logger")
    public static class PacketLogger {
        private final boolean enabled;
        private final boolean dropResponseValue;
        private final String serviceName;
        private final List<String> hideKeywords;
        private final List<String> sendHeaders;
        private final List<String> receiveHeaders;
        private final List<String> registerPattern;
        private final List<String> dropEvent;
        private final String tag;
    }

}
