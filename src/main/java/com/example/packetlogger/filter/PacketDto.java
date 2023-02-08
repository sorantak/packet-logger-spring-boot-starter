package com.example.packetlogger.filter;

import com.example.packetlogger.config.BaseProperties;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.*;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class PacketDto {

    Event event;
    Url url;
    Client client;
    Http http;
    String query;
    String serviceName;
    String type;
    Network network;
    String apiType;
    UserAgent userAgent;
    String method;
    String status;
    Host host;
    int statusCode;
    String tag;

    @Getter
    @AllArgsConstructor
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public static class Event {
        private final long duration;
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
        private final LocalDateTime start;
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
        private final LocalDateTime end;

        @Builder
        public Event(LocalDateTime start, LocalDateTime end) {
            this.duration = ChronoUnit.NANOS.between(start, end);
            this.start = start;
            this.end = end;
        }
    }

    @Getter
    @Builder(builderMethodName = "urlBuilder")
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public static class Url {
        private String full;
        private String path;
        private String query;

        public static UrlBuilder builder(HttpServletRequest request) {
            return urlBuilder()
                    .full(String.format("%s%s", request.getRequestURL(), (request.getQueryString() != null) ? request.getQueryString() : ""))
                    .path((request.getServletPath() != null) ? request.getServletPath() : "")
                    .query(request.getQueryString());
        }
    }

    @Getter
    @Builder(builderMethodName = "clientBuilder")
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public static class Client {
        private String ip;
        private int port;

        public static ClientBuilder builder(HttpServletRequest request) {
            return clientBuilder()
                    .ip(request.getRemoteAddr())
                    .port(request.getRemotePort());
        }
    }

    @Getter
    @Builder(builderMethodName = "httpBuilder")
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public static class Http {
        private final String version = LoggerFilter.VERSION;
        private Request request;
        private Response response;

        public static HttpBuilder builder(HttpServletRequest request, HttpServletResponse response, String reqBody, String resBody, String code, String message, BaseProperties.PacketLogger options) {
            return httpBuilder()
                    .request(Request.builder(request, reqBody, options).build())
                    .response(Response.builder(response, resBody, code, message, options.getReceiveHeaders()).build());
        }
    }

    @Getter
    @Builder
    @AllArgsConstructor
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public static class Network {
        String forwardedIp;
        String direction;
    }

    @Getter
    @Builder
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public static class UserAgent {
        String original;
    }

    @Getter
    @Builder
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public static class Host {
        String name;
    }

    @Getter
    @Builder(builderMethodName = "requestBuilder")
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public static class Request {
        private String method;
        private String referrer;
        private Map headers;
        private Body body;
        private String transactionId;
        private String pathPattern;
        private String pathParam;
        private int bytes;

        public static RequestBuilder builder(HttpServletRequest request, String content, BaseProperties.PacketLogger options) {
            PatternMap patternMap = registerPattern(request.getServletPath(), options.getRegisterPattern());

            Map<String, Object> headersMap = new HashMap<>();
            options.getSendHeaders().forEach(h -> {
                headersMap.put(h, request.getHeader(h));
            });

            return requestBuilder()
                    .method(request.getMethod().toLowerCase())
                    .referrer(request.getHeader(LoggerFilter.REFERRER))
                    .pathPattern(patternMap.pattern)
                    .pathParam(patternMap.param)
                    .headers(headersMap)
                    .body(Body.builder()
                            .bytes(Math.max(request.getContentLength(), 0))
                            .content(content)
                            .build())
                    .transactionId(request.getHeader(LoggerFilter.B3_TRACE_ID))
                    .bytes(Math.max(request.getContentLength(), 0) + getByteSize(headersMap));
        }
    }

    public static PatternMap registerPattern(String reqUrl, List<String> patternUrl) {
        for (String pattern : patternUrl) {
            String[] result = {""};

            PacketDto.UrlMap urlMap = matchUrl(reqUrl, pattern);
            if (urlMap.isMatch()) {
                urlMap.getUrlPair().forEach((k, v) -> {
                    result[0] += k + "=" + v + "&";
                });
                if (result[0].endsWith("&")) {
                    result[0] = result[0].replaceFirst(".$", "");
                }
                return new PatternMap(pattern, result[0]);
            }
        }
        return new PatternMap(reqUrl, null);
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public static class PatternMap {
        private String pattern;
        private String param;
    }

    public static UrlMap matchUrl(String reqUrl, String patternUrl) {
        UrlMap urlMap = new UrlMap();

        if (Objects.equals(reqUrl, patternUrl)) {
            urlMap.isMatch = true;
            urlMap.urlPair = Collections.EMPTY_MAP;
            return urlMap;
        }

        List<String> patterns = List.of(patternUrl.split("/"));
        List<String> paths = List.of(reqUrl.split("/"));
        if (patterns.size() != paths.size()) {
            urlMap.isMatch = false;
            urlMap.urlPair = Collections.EMPTY_MAP;
            return urlMap;
        }

        Map<String, String> params = new LinkedHashMap<>();
        ListIterator<String> iterator = patterns.listIterator();

        while (iterator.hasNext()) {
            String temp = iterator.next();
            int tempIdx = iterator.nextIndex()-1;
            if (temp.startsWith(":")) {
                params.put(temp.split(":")[1], paths.get(tempIdx));
                urlMap.isMatch = true;
                urlMap.urlPair = params;
            } else if (!temp.equals(paths.get(tempIdx))) {
                urlMap.isMatch = false;
                urlMap.urlPair = Collections.EMPTY_MAP;
                return urlMap;
            }
        }

        return urlMap;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public static class UrlMap {
        private boolean isMatch;
        private Map urlPair;
    }

    @Getter
    @Builder(builderMethodName = "responseBuilder")
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public static class Response {
        private String code;
        private String message;
        private Body body;
        private Map<String, Object> headers;
        private int bytes;
        private int statusCode;

        public static ResponseBuilder builder(HttpServletResponse response, String content, String code, String message, List<String> headers) {
            Map<String, Object> headersMap = new HashMap<>();
            headers.forEach(h -> {
                headersMap.put(h, response.getHeader(h));
            });

            RequiredHeader requiredHeader = RequiredHeader.builder()
                    .contentType(response.getContentType())
                    .contentLength(content.getBytes().length)
                    .build();
            ObjectMapper objectMapper = new ObjectMapper();
            headersMap.putAll(objectMapper.convertValue(requiredHeader, Map.class));

            return responseBuilder()
                    .code(code)
                    .message(message)
                    .headers(headersMap)
                    .body(Body.builder()
                            .bytes(content.getBytes().length)
                            .content(content)
                            .build())
                    .bytes(content.getBytes().length + getByteSize(headersMap))
                    .statusCode(response.getStatus());
        }
    }

    @Getter
    @Setter
    @Builder
    @JsonNaming(PropertyNamingStrategies.KebabCaseStrategy.class)
    public static class RequiredHeader {
        private String contentType;
        private int contentLength;
    }

    private static int getByteSize(Map<String, Object> map) {
        try {
            ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
            ObjectOutputStream objectOutputStream = new ObjectOutputStream(byteArrayOutputStream);
            objectOutputStream.writeObject(map);
            objectOutputStream.close();
            return byteArrayOutputStream.size();
        } catch (IOException e) {
            return 0;
        }
    }

    @Getter
    @Setter
    @Builder
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public static class Body {
        private int bytes;
        private String content;
    }
}
