package com.example.packetlogger.filter;

import com.example.packetlogger.config.BaseProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.type.TypeFactory;
import lombok.AllArgsConstructor;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.ObjectUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;
import org.springframework.web.util.WebUtils;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.*;

@Component
@Order(99)
@RequiredArgsConstructor
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = "packet-logger", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(BaseProperties.PacketLogger.class)
public class LoggerFilter extends OncePerRequestFilter {

    static final String VERSION = "1.1";
    static final String PACKET_TYPE = "http";
    static final String USER_AGENT = "user-agent";
    static final String DIRECTION = "ingress";
    static final String MASK = "xxxx";
    static final String CODE = "code";
    static final String MESSAGE = "message";
    static final String OK = "OK";
    static final String NOT_OK = "NOT OK";
    static final String FORWARDED_FOR = "x-forwarded-for";
    static final String B3_TRACE_ID = "x-b3-traceid";
    static final String REFERRER = "referer";
    static final String CONTENT_TYPE = "content-type";
    static final String APPLICATION_JSON = "application/json";

    private final ObjectMapper objectMapper;
    private final BaseProperties.PacketLogger options;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
        if (isAsyncDispatch(request)) {
            filterChain.doFilter(request, response);
        } else {
            doFilterWrapped(wrapRequest(request), wrapResponse(response), filterChain);
        }
    }

    private void doFilterWrapped(ContentCachingRequestWrapper request, ContentCachingResponseWrapper response, FilterChain filterChain) throws ServletException, IOException {
        LocalDateTime start = LocalDateTime.now();

        filterChain.doFilter(request, response);
        doLogger(request, response, start);
        try {
//            doLogger(request, response, start);
        } catch (Exception e) {
            e.printStackTrace();
        }
        response.copyBodyToResponse();
    }

    private ContentCachingRequestWrapper wrapRequest(HttpServletRequest request) {
        return new ContentCachingRequestWrapper(request);
    }

    private ContentCachingResponseWrapper wrapResponse(HttpServletResponse response) {
        return new ContentCachingResponseWrapper(response);
    }

    private void doLogger(ContentCachingRequestWrapper request, ContentCachingResponseWrapper response, LocalDateTime start) throws JsonProcessingException {
        if (options.getDropEvent().contains(request.getServletPath())) return;

        String filteredRequest = doRequest(request);
        ResponseMap filteredResponse = doResponse(response);

        PacketDto packetDto = PacketDto.builder()
                .serviceName(options.getServiceName())
                .type(PACKET_TYPE)
                .apiType(apiType(request))
                .tag(options.getTag())
                .query(query(request))
                .method(request.getMethod().toLowerCase())
                .event(PacketDto.Event.builder()
                        .start(start)
                        .end(LocalDateTime.now())
                        .build())
                .network(PacketDto.Network.builder()
                        .forwardedIp(getIp(request.getHeader(FORWARDED_FOR)))
                        .direction(DIRECTION)
                        .build())
                .url(PacketDto.Url.builder(request).build())
                .client(PacketDto.Client.builder(request).build())
                .host(PacketDto.Host.builder()
                        .name(request.getServerName())
                        .build())
                .userAgent(PacketDto.UserAgent.builder()
                        .original(request.getHeader(USER_AGENT))
                        .build())
                .http(PacketDto.Http.builder(
                                request,
                                response,
                                filteredRequest,
                                (!options.isDropResponseValue()) ? filteredResponse.filteredResponse : "",
                                filteredResponse.code,
                                filteredResponse.message,
                                options)
                        .build())
                .status((response.getStatus() == 200) ? OK : NOT_OK)
                .build();

        System.out.println(objectMapper.writeValueAsString(packetDto));
    }

    private String doRequest(ContentCachingRequestWrapper request) {
        String requestNativeContent = Optional.of(nativeRequest(request)).orElse("");

        Map<String, Object> mapContent = mapContent(objectMapper, requestNativeContent, Optional.ofNullable(request.getHeader(CONTENT_TYPE)).orElse(APPLICATION_JSON));
        try {
            Map<String, Object> filterContent = filterContent(mapContent, options.getHideKeywords(), 2);
            return filterContent.size() == 0 ? "" : objectMapper.writeValueAsString(filterContent);
        } catch (Exception ignored) {
            return requestNativeContent;
        }
    }

    private ResponseMap doResponse(ContentCachingResponseWrapper response) {
        String responseNativeContent = Optional.of(nativeResponse(response)).orElse("");

        try {
            Map<String, Object> responseMaskContent = filterContent(mapContent(objectMapper, responseNativeContent), options.getHideKeywords(), 2);
            return new ResponseMap(objectMapper.writeValueAsString(responseMaskContent), responseMaskContent.get(CODE).toString(), responseMaskContent.get(MESSAGE).toString());
        } catch (Exception e) {
            return new ResponseMap(responseNativeContent, null, null);
        }
    }

    @AllArgsConstructor
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    private static class ResponseMap {
        private String filteredResponse;
        private String code;
        private String message;
    }

    private String query(ContentCachingRequestWrapper request) {
        return request.getMethod() + " " + ((request.getServletPath() != null) ? request.getServletPath() : "");
    }

    private String apiType(ContentCachingRequestWrapper request) {
        if (request.getServletPath().split("/").length > 2) {
            return request.getServletPath().split("/")[2];
        }
        return null;
    }

    private String getIp(String ip) {
        if (!ObjectUtils.isEmpty(ip)) {
            return ip.split(",")[0];
        }
        return null;
    }

    private String nativeRequest(HttpServletRequest request) {
        ContentCachingRequestWrapper wrapper = WebUtils.getNativeRequest(request, ContentCachingRequestWrapper.class);
        return new String(wrapper.getContentAsByteArray());
    }

    private String nativeResponse(HttpServletResponse response) {
        ContentCachingResponseWrapper wrapper = WebUtils.getNativeResponse(response, ContentCachingResponseWrapper.class);
        return new String(wrapper.getContentAsByteArray());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> filterContent(Map<String, Object> map, List<String> hideKeywords, int depth) {
        if (depth <= 0 || map.isEmpty() || hideKeywords.isEmpty()) {
            return map;
        }

        for (Map.Entry<String, Object> entry : map.entrySet()) {
            if (hideKeywords.contains(entry.getKey()) && entry.getValue() instanceof String) {
                entry.setValue(MASK);
            } else if (entry.getValue() instanceof Map) {
                filterContent((Map<String, Object>) entry.getValue(), hideKeywords, depth - 1);
            } else if (entry.getValue() instanceof List) {
                ((List<Map<String, Object>>) entry.getValue()).forEach(
                        s -> filterContent(s, hideKeywords, depth - 1)
                );
            }
        }

        return map;
    }

    private Map<String, Object> mapContent(ObjectMapper objectMapper, String content, String mediaType) {
        Arrays.stream(mediaType.split(";")).filter(i -> (i.contains(APPLICATION_JSON)) ? true : null);
        return mapContent(objectMapper, content);
    }

    private Map<String, Object> mapContent(ObjectMapper objectMapper, String content) {
        try {
            return objectMapper.readValue(content, TypeFactory.defaultInstance().constructMapType(HashMap.class, String.class, Object.class));
        } catch (JsonProcessingException e) {
            return Collections.emptyMap();
        }
    }
}
