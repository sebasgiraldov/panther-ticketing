package com.panther.ticketing.driven.dynamodb.converter;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

import static com.panther.ticketing.driven.dynamodb.converter.AttributeValueConverter.*;
import static org.assertj.core.api.Assertions.assertThat;

class AttributeValueConverterTest {

    @Test
    void s_nonNull_returnsStringAttribute() {
        assertThat(s("hello").s()).isEqualTo("hello");
    }

    @Test
    void s_null_returnsNulAttribute() {
        assertThat(s(null).nul()).isTrue();
    }

    @Test
    void n_number_returnsNumberAttribute() {
        assertThat(n(42).n()).isEqualTo("42");
    }

    @Test
    void n_null_returnsNulAttribute() {
        assertThat(n(null).nul()).isTrue();
    }

    @Test
    void bool_returnsBoolean() {
        assertThat(bool(true).bool()).isTrue();
        assertThat(bool(false).bool()).isFalse();
    }

    @Test
    void getString_present_returnsValue() {
        Map<String, AttributeValue> item = Map.of("key", AttributeValue.builder().s("val").build());
        assertThat(getString(item, "key")).isEqualTo("val");
    }

    @Test
    void getString_missing_returnsNull() {
        assertThat(getString(Map.of(), "key")).isNull();
    }

    @Test
    void getString_nullS_returnsNull() {
        Map<String, AttributeValue> item = Map.of("key", AttributeValue.builder().nul(true).build());
        assertThat(getString(item, "key")).isNull();
    }

    @Test
    void getUUID_present_returnsUUID() {
        UUID id = UUID.randomUUID();
        Map<String, AttributeValue> item = Map.of("id", AttributeValue.builder().s(id.toString()).build());
        assertThat(getUUID(item, "id")).isEqualTo(id);
    }

    @Test
    void getUUID_missing_returnsNull() {
        assertThat(getUUID(Map.of(), "id")).isNull();
    }

    @Test
    void getInt_present_returnsInt() {
        Map<String, AttributeValue> item = Map.of("qty", AttributeValue.builder().n("5").build());
        assertThat(getInt(item, "qty")).isEqualTo(5);
    }

    @Test
    void getInt_missing_returnsZero() {
        assertThat(getInt(Map.of(), "qty")).isZero();
    }

    @Test
    void getLong_present_returnsLong() {
        Map<String, AttributeValue> item = Map.of("v", AttributeValue.builder().n("99").build());
        assertThat(getLong(item, "v")).isEqualTo(99L);
    }

    @Test
    void getLong_missing_returnsZero() {
        assertThat(getLong(Map.of(), "v")).isZero();
    }

    @Test
    void getBigDecimal_present_returnsBigDecimal() {
        Map<String, AttributeValue> item = Map.of("price", AttributeValue.builder().n("50000.00").build());
        assertThat(getBigDecimal(item, "price")).isEqualByComparingTo(new BigDecimal("50000.00"));
    }

    @Test
    void getBigDecimal_missing_returnsNull() {
        assertThat(getBigDecimal(Map.of(), "price")).isNull();
    }

    @Test
    void getDateTime_present_returnsLocalDateTime() {
        LocalDateTime dt = LocalDateTime.of(2026, 1, 1, 10, 0);
        Map<String, AttributeValue> item = Map.of("ts", AttributeValue.builder().s(formatDateTime(dt)).build());
        assertThat(getDateTime(item, "ts")).isEqualTo(dt);
    }

    @Test
    void getDateTime_missing_returnsNull() {
        assertThat(getDateTime(Map.of(), "ts")).isNull();
    }

    @Test
    void formatDateTime_null_returnsNull() {
        assertThat(formatDateTime(null)).isNull();
    }
}
