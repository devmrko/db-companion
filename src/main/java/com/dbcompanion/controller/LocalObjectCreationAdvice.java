package com.dbcompanion.controller;

import com.dbcompanion.common.exception.ObjectCreationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes=LocalObjectCreationController.class)
public class LocalObjectCreationAdvice {
    @ExceptionHandler(ObjectCreationException.class)
    public ResponseEntity<?> creation(ObjectCreationException ex){return ResponseEntity.status(ex.status()).header("Cache-Control","no-store").body(ex.response());}
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<?> input(){return creation(ObjectCreationException.from(new IllegalArgumentException(),"VALIDATION",false));}
}
