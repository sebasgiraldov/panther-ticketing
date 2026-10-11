package com.panther.ticketing.driven.dynamodb.converter;

import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;

public final class AttributeValueConverter {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private AttributeValueConverter() {}

    public static AttributeValue s(String value) {
        return value != null ? AttributeValue.builder().s(value).build() : AttributeValue.builder().nul(true).build();
    }

    public static AttributeValue n(Number value) {
        return value != null ? AttributeValue.builder().n(value.toString()).build() : AttributeValue.builder().nul(true).build();
    }

    public static AttributeValue bool(boolean value) {
        return AttributeValue.builder().bool(value).build();
    }

    public static String getString(Map<String, AttributeValue> item, String key) {
        AttributeValue v = item.get(key);
        return v != null && v.s() != null ? v.s() : null;
    }

    public static UUID getUUID(Map<String, AttributeValue> item, String key) {
        String v = getString(item, key);
        return v != null ? UUID.fromString(v) : null;
    }

    public static int getInt(Map<String, AttributeValue> item, String key) {
        AttributeValue v = item.get(key);
        return v != null && v.n() != null ? Integer.parseInt(v.n()) : 0;
    }

    public static long getLong(Map<String, AttributeValue> item, String key) {
        AttributeValue v = item.get(key);
        return v != null && v.n() != null ? Long.parseLong(v.n()) : 0L;
    }

    public static BigDecimal getBigDecimal(Map<String, AttributeValue> item, String key) {
        AttributeValue v = item.get(key);
        return v != null && v.n() != null ? new BigDecimal(v.n()) : null;
    }

    public static LocalDateTime getDateTime(Map<String, AttributeValue> item, String key) {
        String v = getString(item, key);
        return v != null ? LocalDateTime.parse(v, FMT) : null;
    }

    public static String formatDateTime(LocalDateTime dt) {
        return dt != null ? dt.format(FMT) : null;
    }
}
