package com.persimmon.ddd.web;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.persimmon.ddd.application.Cursor;
import java.io.IOException;

/** A {@link Cursor} is a plain JSON string on the wire, not an object with a {@code value}. */
public class CursorJacksonModule extends SimpleModule {
  public CursorJacksonModule() {
    super("persimmon-ddd-cursor");
    addSerializer(
        Cursor.class,
        new JsonSerializer<>() {
          @Override
          public void serialize(Cursor value, JsonGenerator gen, SerializerProvider provider)
              throws IOException {
            gen.writeString(value.value());
          }
        });
    addDeserializer(
        Cursor.class,
        new JsonDeserializer<>() {
          @Override
          public Cursor deserialize(JsonParser parser, DeserializationContext context)
              throws IOException {
            String value = parser.getValueAsString();
            return value == null || value.isBlank() ? null : new Cursor(value);
          }
        });
  }
}
