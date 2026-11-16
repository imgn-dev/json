package be.imgn.json.tests;

import java.util.List;

/// A triangle.
///
/// @param sides the lengths of its sides
public record Triangle(List<Double> sides) implements Polygon {}
