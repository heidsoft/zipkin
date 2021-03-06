/**
 * Copyright 2015-2017 The OpenZipkin Authors
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
package zipkin.internal.v2;

import com.google.auto.value.AutoValue;
import java.io.Serializable;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;
import zipkin.Constants;
import zipkin.TraceKeys;
import zipkin.internal.v2.codec.BytesEncoder;

/**
 * A trace is a series of spans (often RPC calls) which form a latency tree.
 *
 * <p>Spans are usually created by instrumentation in RPC clients or servers, but can also represent
 * in-process activity. Annotations in spans are similar to log statements, and are sometimes
 * created directly by application developers to indicate events of interest, such as a cache miss.
 *
 * <p>The root span is where {@link #parentId} is null; it usually has the longest {@link #duration}
 * in the trace.
 *
 * <p>Span identifiers are packed into longs, but should be treated opaquely. ID encoding is 16 or
 * 32 character lower-hex, to avoid signed interpretation. * This is a single-host view of a {@link
 * zipkin.Span}: the primary way tracers record data.
 *
 * <h3>Relationship to {@link zipkin.Span}</h3> <p>This type is intended to replace use of {@link
 * zipkin.Span}. Particularly, tracers represent a single-host view of an operation. By making one
 * endpoint implicit for all data, this type does not need to repeat endpoints on each data like
 * {@link zipkin.Span span} does. This results in simpler and smaller data.
 */
@AutoValue
@Immutable
public abstract class Span implements Serializable { // for Spark jobs
  static final Charset UTF_8 = Charset.forName("UTF-8");

  private static final long serialVersionUID = 0L;

  /**
   * Trace identifier, set on all spans within it.
   *
   * <p>Encoded as 16 or 32 lowercase hex characters corresponding to 64 or 128 bits. For example, a
   * 128bit trace ID looks like {@code 4e441824ec2b6a44ffdc9bb9a6453df3}.
   *
   * <p>Some systems downgrade trace identifiers to 64bit by dropping the left-most 16 characters.
   * For example, {@code 4e441824ec2b6a44ffdc9bb9a6453df3} becomes {@code ffdc9bb9a6453df3}.
   */
  public abstract String traceId();

  /**
   * The parent's {@link #id} or null if this the root span in a trace.
   *
   * <p>This is the same encoding as {@link #id}. For example {@code ffdc9bb9a6453df3}
   */
  @Nullable public abstract String parentId();

  /**
   * Unique 64bit identifier for this operation within the trace.
   *
   * <p>Encoded as 16 lowercase hex characters. For example {@code ffdc9bb9a6453df3}
   *
   * <p>A span is uniquely identified in storage by ({@linkplain #traceId}, {@linkplain #id()}).
   */
  public abstract String id();

  /** Indicates the primary span type. */
  public enum Kind {
    CLIENT,
    SERVER,
    /**
     * When present, {@link #timestamp()} is the moment a producer sent a message to a destination.
     * {@link #duration()} represents delay sending the message, such as batching, while {@link
     * #remoteEndpoint()} indicates the destination, such as a broker.
     *
     * <p>Unlike {@link #CLIENT}, messaging spans never share a span ID. For example, the {@link
     * #CONSUMER} of the same message has {@link #parentId()} set to this span's {@link #id()}.
     */
    PRODUCER,
    /**
     * When present, {@link #timestamp()} is the moment a consumer received a message from an
     * origin. {@link #duration()} represents delay consuming the message, such as from backlog,
     * while {@link #remoteEndpoint()} indicates the origin, such as a broker.
     *
     * <p>Unlike {@link #SERVER}, messaging spans never share a span ID. For example, the {@link
     * #PRODUCER} of this message is the {@link #parentId()} of this span.
     */
    CONSUMER
  }

  /** When present, used to interpret {@link #remoteEndpoint} */
  @Nullable public abstract Kind kind();

  /**
   * Span name in lowercase, rpc method for example.
   *
   * <p>Conventionally, when the span name isn't known, name = "unknown".
   */
  @Nullable public abstract String name();

  /**
   * Epoch microseconds of the start of this span, possibly absent if this an incomplete span.
   *
   * <p>This value should be set directly by instrumentation, using the most precise value possible.
   * For example, {@code gettimeofday} or multiplying {@link System#currentTimeMillis} by 1000.
   *
   * <p>There are three known edge-cases where this could be reported absent:
   *
   * <pre><ul>
   * <li>A span was allocated but never started (ex not yet received a timestamp)</li>
   * <li>The span's start event was lost</li>
   * <li>Data about a completed span (ex tags) were sent after the fact</li>
   * </pre><ul>
   *
   * @see #duration()
   */
  @Nullable public abstract Long timestamp();

  /**
   * Measurement in microseconds of the critical path, if known. Durations of less than one
   * microsecond must be rounded up to 1 microsecond.
   *
   * <p>This value should be set directly, as opposed to implicitly via annotation timestamps. Doing
   * so encourages precision decoupled from problems of clocks, such as skew or NTP updates causing
   * time to move backwards.
   *
   * <p>For compatibility with instrumentation that precede this field, collectors or span stores
   * can derive this by subtracting {@link Annotation#timestamp}. For example, {@link
   * Constants#SERVER_SEND}.timestamp - {@link Constants#SERVER_RECV}.timestamp.
   *
   * <p>If this field is persisted as unset, zipkin will continue to work, except duration query
   * support will be implementation-specific. Similarly, setting this field non-atomically is
   * implementation-specific.
   *
   * <p>This field is i64 vs i32 to support spans longer than 35 minutes.
   */
  @Nullable public abstract Long duration();

  /**
   * The host that recorded this span, primarily for query by service name.
   *
   * <p>Instrumentation should always record this and be consistent as possible with the service
   * name as it is used in search. This is nullable for legacy reasons.
   */
  // Nullable for data conversion especially late arriving data which might not have an annotation
  @Nullable public abstract Endpoint localEndpoint();

  /**
   * When an RPC (or messaging) span, indicates the other side of the connection.
   *
   * <p>By recording the remote endpoint, your trace will contain network context even if the peer
   * is not tracing. For example,For example, you can record the IP from the {@code X-Forwarded-For}
   * header or or the service name and socket of a remote peer.
   */
  @Nullable public abstract Endpoint remoteEndpoint();

  /**
   * Events that explain latency with a timestamp. Unlike log statements, annotations are often
   * short or contain codes: for example "brave.flush". Annotations are sorted ascending by
   * timestamp.
   */
  public abstract List<Annotation> annotations();

  /**
   * Tags a span with context, usually to support query or aggregation.
   *
   * <p>For example, a tag key could be {@link TraceKeys#HTTP_PATH "http.path"}.
   */
  public abstract Map<String, String> tags();

  /** True is a request to store this span even if it overrides sampling policy. */
  @Nullable public abstract Boolean debug();

  /**
   * True if we are contributing to a span started by another tracer (ex on a different host).
   * Defaults to null. When set, it is expected for {@link #kind()} to be {@link Kind#SERVER}.
   *
   * <p>When an RPC trace is client-originated, it will be sampled and the same span ID is used for
   * the server side. However, the server shouldn't set span.timestamp or duration since it didn't
   * start the span.
   */
  @Nullable public abstract Boolean shared();

  @Nullable public String localServiceName() {
    Endpoint localEndpoint = localEndpoint();
    return localEndpoint != null ? localEndpoint.serviceName() : null;
  }

  @Nullable public String remoteServiceName() {
    Endpoint remoteEndpoint = remoteEndpoint();
    return remoteEndpoint != null ? remoteEndpoint.serviceName() : null;
  }

  public static Builder newBuilder() {
    return new Builder();
  }

  public Builder toBuilder() {
    return new Builder(this);
  }

  public static final class Builder {
    String traceId;
    String parentId;
    String id;
    Kind kind;
    String name;
    Long timestamp;
    Long duration;
    Endpoint localEndpoint;
    Endpoint remoteEndpoint;
    ArrayList<Annotation> annotations;
    TreeMap<String, String> tags;
    Boolean debug;
    Boolean shared;

    public Builder clear() {
      traceId = null;
      parentId = null;
      id = null;
      kind = null;
      name = null;
      timestamp = null;
      duration = null;
      localEndpoint = null;
      remoteEndpoint = null;
      if (annotations != null) annotations.clear();
      if (tags != null) tags.clear();
      debug = null;
      shared = null;
      return this;
    }

    @Override public Builder clone() {
      Builder result = new Builder();
      result.traceId = traceId;
      result.parentId = parentId;
      result.id = id;
      result.kind = kind;
      result.name = name;
      result.timestamp = timestamp;
      result.duration = duration;
      result.localEndpoint = localEndpoint;
      result.remoteEndpoint = remoteEndpoint;
      if (annotations != null) {
        result.annotations = (ArrayList) annotations.clone();
      }
      if (tags != null) {
        result.tags = (TreeMap) tags.clone();
      }
      result.debug = debug;
      result.shared = shared;
      return result;
    }

    Builder(Span source) {
      traceId = source.traceId();
      parentId = source.parentId();
      id = source.id();
      kind = source.kind();
      name = source.name();
      timestamp = source.timestamp();
      duration = source.duration();
      localEndpoint = source.localEndpoint();
      remoteEndpoint = source.remoteEndpoint();
      if (!source.annotations().isEmpty()) {
        annotations = new ArrayList<>(source.annotations().size());
        annotations.addAll(source.annotations());
      }
      if (!source.tags().isEmpty()) {
        tags = new TreeMap<>();
        tags.putAll(source.tags());
      }
      debug = source.debug();
      shared = source.shared();
    }

    @Nullable public Kind kind() {
      return kind;
    }

    @Nullable public Endpoint localEndpoint() {
      return localEndpoint;
    }

    /**
     * @throws IllegalArgumentException if not lower-hex format
     * @see Span#id()
     */
    public Builder traceId(String traceId) {
      this.traceId = normalizeTraceId(traceId);
      return this;
    }

    /**
     * @throws IllegalArgumentException if not lower-hex format
     * @see Span#parentId()
     */
    public Builder parentId(@Nullable String parentId) {
      if (parentId != null) {
        int length = parentId.length();
        if (length > 16) throw new IllegalArgumentException("parentId.length > 16");
        validateHex(parentId);
        this.parentId = length < 16 ? padLeft(parentId, 16) : parentId;
      }
      return this;
    }

    /**
     * @throws IllegalArgumentException if not lower-hex format
     * @see Span#id()
     */
    public Builder id(String id) {
      if (id == null) throw new NullPointerException("id == null");
      int length = id.length();
      if (length > 16) throw new IllegalArgumentException("id.length > 16");
      validateHex(id);
      this.id = length < 16 ? padLeft(id, 16) : id;
      return this;
    }

    /** @see Span#kind */
    public Builder kind(@Nullable Kind kind) {
      this.kind = kind;
      return this;
    }

    /** @see Span#name */
    public Builder name(@Nullable String name) {
      this.name = name == null || name.isEmpty() ? null : name.toLowerCase(Locale.ROOT);
      return this;
    }

    /** @see Span#timestamp */
    public Builder timestamp(@Nullable Long timestamp) {
      if (timestamp != null && timestamp == 0L) timestamp = null;
      this.timestamp = timestamp;
      return this;
    }

    /** @see Span#duration */
    public Builder duration(@Nullable Long duration) {
      if (duration != null && duration == 0L) duration = null;
      this.duration = duration;
      return this;
    }

    /** @see Span#localEndpoint */
    public Builder localEndpoint(@Nullable Endpoint localEndpoint) {
      this.localEndpoint = localEndpoint;
      return this;
    }

    /** @see Span#remoteEndpoint */
    public Builder remoteEndpoint(@Nullable Endpoint remoteEndpoint) {
      this.remoteEndpoint = remoteEndpoint;
      return this;
    }

    /** @see Span#annotations */
    public Builder addAnnotation(long timestamp, String value) {
      if (annotations == null) annotations = new ArrayList<>(2);
      annotations.add(Annotation.create(timestamp, value));
      return this;
    }

    /** @see Span#tags */
    public Builder putTag(String key, String value) {
      if (tags == null) tags = new TreeMap<>();
      if (key == null) throw new NullPointerException("key == null");
      if (value == null) throw new NullPointerException("value of " + key + " == null");
      this.tags.put(key, value);
      return this;
    }

    /** @see Span#debug */
    public Builder debug(@Nullable Boolean debug) {
      this.debug = debug;
      return this;
    }

    /** @see Span#shared */
    public Builder shared(@Nullable Boolean shared) {
      this.shared = shared;
      return this;
    }

    public Span build() {
      return new AutoValue_Span(
        traceId,
        parentId,
        id,
        kind,
        name,
        timestamp,
        duration,
        localEndpoint,
        remoteEndpoint,
        sortedList(annotations),
        tags == null ? Collections.emptyMap() : new LinkedHashMap<>(tags),
        debug,
        shared
      );
    }

    Builder() {
    }
  }

  @Override public String toString() {
    return new String(BytesEncoder.JSON.encode(this), UTF_8);
  }

  /**
   * Returns a valid lower-hex trace ID, padded left as needed to 16 or 32 characters.
   *
   * @throws IllegalArgumentException if oversized or not lower-hex
   */
  public static String normalizeTraceId(String traceId) {
    if (traceId == null) throw new NullPointerException("traceId == null");
    int length = traceId.length();
    if (length > 32) throw new IllegalArgumentException("traceId.length > 32");
    validateHex(traceId);
    if (length == 32 || length == 16) {
      return traceId;
    } else if (length < 16) {
      return padLeft(traceId, 16);
    } else {
      return padLeft(traceId, 32);
    }
  }

  static String padLeft(String id, int desiredLength) {
    StringBuilder builder = new StringBuilder(desiredLength);
    int offset = desiredLength - id.length();

    for (int i = 0; i < offset; i++) builder.append('0');
    builder.append(id);
    return builder.toString();
  }

  static void validateHex(String id) {
    for (int i = 0, length = id.length(); i < length; i++) {
      char c = id.charAt(i);
      if ((c < '0' || c > '9') && (c < 'a' || c > 'f')) {
        throw new IllegalArgumentException(id + " should be lower-hex encoded with no prefix");
      }
    }
  }

  static <T extends Comparable<? super T>> List<T> sortedList(@Nullable List<T> in) {
    if (in == null || in.isEmpty()) return Collections.emptyList();
    if (in.size() == 1) return Collections.singletonList(in.get(0));
    Object[] array = in.toArray();
    Arrays.sort(array);
    List result = Arrays.asList(array);
    return Collections.unmodifiableList(result);
  }
}
