package com.rishikesh.placementprep.modules.agent.dto;

/** One piece of the assistant's reply, sent as a "token" event while it is being written. */
public record ChatChunk(String text) {}
