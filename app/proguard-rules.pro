# Open source: shrink and optimize, but keep names so crash logs read as-is.
-dontobfuscate

# Constructed only from nfs_jni.c.
-keep class dev.vibenfs.nfs.NfsEntry { <init>(java.lang.String, boolean, long, long); }

# Glance instantiates widget actions by reflection from the class name in the click intent.
-keep class * implements androidx.glance.appwidget.action.ActionCallback { <init>(); }
