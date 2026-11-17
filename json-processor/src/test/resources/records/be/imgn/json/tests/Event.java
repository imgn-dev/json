package be.imgn.json.tests;

import be.imgn.json.bind.JsonRecord;
import be.imgn.json.bind.JsonWrapper;


/// An event, laid out as a wrapper object.
@JsonRecord
@JsonWrapper
public sealed interface Event permits Created, Deleted {}
