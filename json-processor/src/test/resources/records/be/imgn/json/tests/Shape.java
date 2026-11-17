package be.imgn.json.tests;

import be.imgn.json.bind.JsonRecord;
import be.imgn.json.bind.JsonTagMember;


/// A shape, laid out with a tag member, through a nested sealed interface.
@JsonRecord
@JsonTagMember("kind")
public sealed interface Shape permits Circle, Polygon {}
