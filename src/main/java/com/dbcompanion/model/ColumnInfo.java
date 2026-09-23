package com.dbcompanion.model;

public record ColumnInfo(int position, String name, String dataType, String nullable, String comment) {}
