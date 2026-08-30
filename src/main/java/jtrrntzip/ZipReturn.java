package jtrrntzip;

/**
 * The detailed result codes of the archive operations defined by
 * {@link jtrrntzip.supportedfiles.ICompress}.
 *
 * <p>{@link #ZIPGOOD} signals success, every other value names the structural
 * problem or I/O condition that aborted the operation. The value space is
 * inherited from the original C# implementation; values marked as reserved are
 * never produced by this project but kept so the documented result space
 * stays complete.</p>
 */
public enum ZipReturn {
    /**
     * The operation completed successfully.
     */
    ZIPGOOD,
    /**
     * The archive file is locked by another process. Reserved, not produced
     * by this project.
     */
    ZIPFILELOCKED,
    /**
     * The entry count stored in the archive is inconsistent. Reserved, not
     * produced by this project.
     */
    ZIPFILECOUNTERROR,
    /**
     * A structure signature does not match its expected value. Reserved, not
     * produced by this project.
     */
    ZIPSIGNATUREERROR,
    /**
     * Trailing bytes follow the end of the archive. Reserved, not produced by
     * this project; trailing bytes are reported as
     * {@link ZipStatus#EXTRADATA} instead.
     */
    ZIPEXTRADATAONENDOFZIP,
    /**
     * An entry uses a compression method other than deflate (8) or stored
     * (0).
     */
    ZIPUNSUPPORTEDCOMPRESSION,
    /**
     * A local file header is invalid or disagrees with its central directory
     * entry.
     */
    ZIPLOCALFILEHEADERERROR,
    /**
     * The central directory could not be located or parsed.
     */
    ZIPCENTRALDIRERROR,
    /**
     * The end of central directory record is invalid, or the zip64 structures
     * expected in front of it are missing.
     */
    ZIPENDOFCENTRALDIRECTORYERROR,
    /**
     * The zip64 end of central directory record is invalid.
     */
    ZIP64ENDOFCENTRALDIRERROR,
    /**
     * The zip64 end of central directory locator is invalid.
     */
    ZIP64ENDOFCENTRALDIRECTORYLOCATORERROR,
    /**
     * A read stream was requested from an archive that is open for writing.
     */
    ZIPREADINGFROMOUTPUTFILE,
    /**
     * A write stream was requested from an archive that is open for reading.
     */
    ZIPWRITINGTOINPUTFILE,
    /**
     * The data stream of an entry could not be created. Reserved, not
     * produced by this project.
     */
    ZIPERRORGETTINGDATASTREAM,
    /**
     * The CRC-32 of a decoded entry does not match the stored value.
     * Reserved, not produced by this project.
     */
    ZIPCRCDECODEERROR,
    /**
     * Entry data could not be decoded. Reserved, not produced by this
     * project.
     */
    ZIPDECODEERROR,
    /**
     * An entry file name exceeds the maximum storable length. Reserved, not
     * produced by this project.
     */
    ZIPFILENAMETOLONG,
    /**
     * The archive is already open and cannot be opened or created again
     * before being closed.
     */
    ZIPFILEALREADYOPEN,
    /**
     * The archive could not be opened without reading its headers. Reserved,
     * not produced by this project.
     */
    ZIPCANNOTFASTOPEN,
    /**
     * The archive file exists but could not be opened for reading.
     */
    ZIPERROROPENINGFILE,
    /**
     * The archive file does not exist.
     */
    ZIPERRORFILENOTFOUND,
    /**
     * Reading a structure failed, for example because the archive ends
     * unexpectedly or a header is not consistent.
     */
    ZIPERRORREADINGFILE,
    /**
     * The archive was modified between selection and opening, so the
     * caller-provided timestamp no longer matches.
     */
    ZIPERRORTIMESTAMP,
    /**
     * A failed archive could not be rolled back to its previous state.
     * Reserved, not produced by this project.
     */
    ZIPERRORROLLBACKFILE,
    /**
     * A path refers to a directory instead of an archive file. Reserved, not
     * produced by this project.
     */
    ZIPTRYINGTOACCESSADIRECTORY,
    /**
     * A freshly created entry has not been tested yet; the initial per-entry
     * state before any operation reported a result.
     */
    ZIPUNTESTED
}
