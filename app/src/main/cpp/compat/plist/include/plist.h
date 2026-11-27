/* Shim: the protocol sources include "plist/include/plist.h" (the Windows
 * prebuilt libplist layout). On Android, libplist is built from the vendored
 * sources in airplay/lib/plist. */
#include "../../../airplay/lib/plist/plist/plist.h"
