package veil.utils;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * Shared Jackson ObjectMapper configuration for VEIL.
 */
public final class JsonUtil {

    private static final ObjectMapper MAPPER = createMapper(false);
    private static final ObjectMapper PRETTY_MAPPER = createMapper(true);

    private JsonUtil() {
    }

    private static ObjectMapper createMapper(boolean pretty) {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
        mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        if (pretty) {
            mapper.enable(SerializationFeature.INDENT_OUTPUT);
        }
        return mapper;
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    public static ObjectMapper prettyMapper() {
        return PRETTY_MAPPER;
    }
}
