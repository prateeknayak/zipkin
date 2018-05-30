/*
 * Copyright 2015-2018 The OpenZipkin Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package zipkin2.internal;

import java.nio.ByteBuffer;
import zipkin2.Endpoint;
import zipkin2.v1.V1Annotation;
import zipkin2.v1.V1BinaryAnnotation;
import zipkin2.v1.V1Span;

import static zipkin2.internal.ThriftCodec.UTF_8;
import static zipkin2.internal.ThriftCodec.readByteArray;
import static zipkin2.internal.ThriftCodec.readListLength;
import static zipkin2.internal.ThriftCodec.readUtf8;
import static zipkin2.internal.ThriftCodec.skip;
import static zipkin2.internal.ThriftField.TYPE_I32;
import static zipkin2.internal.ThriftField.TYPE_I64;
import static zipkin2.internal.ThriftField.TYPE_STOP;
import static zipkin2.internal.ThriftField.TYPE_STRING;
import static zipkin2.internal.ThriftField.TYPE_STRUCT;
import static zipkin2.internal.V1ThriftSpanWriter.ANNOTATIONS;
import static zipkin2.internal.V1ThriftSpanWriter.BINARY_ANNOTATIONS;
import static zipkin2.internal.V1ThriftSpanWriter.DEBUG;
import static zipkin2.internal.V1ThriftSpanWriter.DURATION;
import static zipkin2.internal.V1ThriftSpanWriter.ID;
import static zipkin2.internal.V1ThriftSpanWriter.NAME;
import static zipkin2.internal.V1ThriftSpanWriter.PARENT_ID;
import static zipkin2.internal.V1ThriftSpanWriter.TIMESTAMP;
import static zipkin2.internal.V1ThriftSpanWriter.TRACE_ID;
import static zipkin2.internal.V1ThriftSpanWriter.TRACE_ID_HIGH;

public final class V1ThriftSpanReader {
  V1Span.Builder builder = V1Span.builder();

  public V1Span read(ByteBuffer bytes) {
    if (builder == null) {
      builder = V1Span.builder();
    } else {
      builder.clear();
    }

    ThriftField thriftField;

    while (true) {
      thriftField = ThriftField.read(bytes);
      if (thriftField.type == TYPE_STOP) break;

      if (thriftField.isEqualTo(TRACE_ID_HIGH)) {
        builder.traceIdHigh(bytes.getLong());
      } else if (thriftField.isEqualTo(TRACE_ID)) {
        builder.traceId(bytes.getLong());
      } else if (thriftField.isEqualTo(NAME)) {
        builder.name(readUtf8(bytes));
      } else if (thriftField.isEqualTo(ID)) {
        builder.id(bytes.getLong());
      } else if (thriftField.isEqualTo(PARENT_ID)) {
        builder.parentId(bytes.getLong());
      } else if (thriftField.isEqualTo(ANNOTATIONS)) {
        int length = readListLength(bytes);
        for (int i = 0; i < length; i++) {
          builder.addAnnotation(AnnotationReader.read(bytes));
        }
      } else if (thriftField.isEqualTo(BINARY_ANNOTATIONS)) {
        int length = readListLength(bytes);
        for (int i = 0; i < length; i++) {
          builder.addBinaryAnnotation(BinaryAnnotationReader.read(bytes));
        }
      } else if (thriftField.isEqualTo(DEBUG)) {
        builder.debug(bytes.get() == 1);
      } else if (thriftField.isEqualTo(TIMESTAMP)) {
        builder.timestamp(bytes.getLong());
      } else if (thriftField.isEqualTo(DURATION)) {
        builder.duration(bytes.getLong());
      } else {
        skip(bytes, thriftField.type);
      }
    }

    return builder.build();
  }

  static final class AnnotationReader {
    static final ThriftField TIMESTAMP = new ThriftField(TYPE_I64, 1);
    static final ThriftField VALUE = new ThriftField(TYPE_STRING, 2);
    static final ThriftField ENDPOINT = new ThriftField(TYPE_STRUCT, 3);

    static V1Annotation read(ByteBuffer bytes) {
      long timestamp = 0;
      String value = null;
      Endpoint endpoint = null;

      ThriftField thriftField;
      while (true) {
        thriftField = ThriftField.read(bytes);
        if (thriftField.type == TYPE_STOP) break;

        if (thriftField.isEqualTo(TIMESTAMP)) {
          timestamp = bytes.getLong();
        } else if (thriftField.isEqualTo(VALUE)) {
          value = readUtf8(bytes);
        } else if (thriftField.isEqualTo(ENDPOINT)) {
          endpoint = ThriftEndpointCodec.read(bytes);
        } else {
          skip(bytes, thriftField.type);
        }
      }

      if (timestamp == 0 || value == null) return null;
      return V1Annotation.create(timestamp, value, endpoint);
    }
  }

  static final class BinaryAnnotationReader {
    static final ThriftField KEY = new ThriftField(TYPE_STRING, 1);
    static final ThriftField VALUE = new ThriftField(TYPE_STRING, 2);
    static final ThriftField TYPE = new ThriftField(TYPE_I32, 3);
    static final ThriftField ENDPOINT = new ThriftField(TYPE_STRUCT, 4);

    static V1BinaryAnnotation read(ByteBuffer bytes) {
      String key = null;
      byte[] value = null;
      Endpoint endpoint = null;
      boolean isBoolean = false;
      boolean isString = false;

      while (true) {
        ThriftField thriftField = ThriftField.read(bytes);
        if (thriftField.type == TYPE_STOP) break;
        if (thriftField.isEqualTo(KEY)) {
          key = readUtf8(bytes);
        } else if (thriftField.isEqualTo(VALUE)) {
          value = readByteArray(bytes);
        } else if (thriftField.isEqualTo(TYPE)) {
          switch (bytes.getInt()) {
            case 0:
              isBoolean = true;
              break;
            case 6:
              isString = true;
              break;
          }
        } else if (thriftField.isEqualTo(ENDPOINT)) {
          endpoint = ThriftEndpointCodec.read(bytes);
        } else {
          skip(bytes, thriftField.type);
        }
      }
      if (value == null) return null;
      if (isString) {
        return V1BinaryAnnotation.createString(key, new String(value, UTF_8), endpoint);
      }
      if (isBoolean && value.length == 1 && value[0] == 1 && endpoint != null) {
        return V1BinaryAnnotation.createAddress(key, endpoint);
      }
      return null; // toss unsupported data
    }
  }
}
