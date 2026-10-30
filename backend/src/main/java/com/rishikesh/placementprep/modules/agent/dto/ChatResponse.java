package com.rishikesh.placementprep.modules.agent.dto;

import java.util.List;

/**
 * The assistant's reply to one message.
 *
 * @param toolsUsed the tools it called to answer - "eligibleDrives", "applyToDrive" and so
 *                  on - each once, in the order first called; empty when it answered
 *                  without looking anything up
 */
public record ChatResponse(String reply, List<String> toolsUsed) {}
