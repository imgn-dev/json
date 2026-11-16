package be.imgn.json.tests;

/// A polygon: a sealed interface within a sealed interface.
public sealed interface Polygon extends Shape permits Rectangle, Triangle {}
