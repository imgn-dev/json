package be.imgn.json.tests;

import be.imgn.json.JsonRecord;
import be.imgn.json.JsonTagMember;

/// A shape, laid out with a tag member, through a nested sealed interface.
@JsonRecord
@JsonTagMember("kind")
public sealed interface Shape permits Circle, Polygon {}
