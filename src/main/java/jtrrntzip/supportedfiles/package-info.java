/**
 * Archive independent abstractions shared by the supported archive formats.
 *
 * <p>{@link ICompress} is the sealed interface every supported archive format
 * implements; {@link jtrrntzip.ZipOpenType}, {@link jtrrntzip.ZipReturn} and
 * {@link jtrrntzip.ZipStatus} describe its open state, its operation results
 * and the extra state observed in an opened archive.
 * {@link EnhancedSeekableByteChannel} adds byte order aware primitive reading
 * and writing plus optional checksumming to any
 * {@link java.nio.channels.SeekableByteChannel}, and {@link UnsignedTypes}
 * converts between the unsigned values used by on-disk structures and the
 * signed Java primitives.</p>
 */
package jtrrntzip.supportedfiles;
