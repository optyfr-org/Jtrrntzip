package jtrrntzip;

import java.io.Serializable;

public enum TrrntZipStatus implements Serializable
{
	VALIDTRRNTZIP,
	CORRUPTZIP,
	NOTTRRNTZIPPED,
	BADDIRECTORYSEPARATOR,
	UNSORTED,
	EXTRADIRECTORYENTRIES,
	REPEATFILESFOUND;
}
