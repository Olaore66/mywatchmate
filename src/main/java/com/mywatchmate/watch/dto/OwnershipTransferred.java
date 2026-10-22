package com.mywatchmate.watch.dto;

/** Data of the OWNERSHIP_TRANSFERRED event. The previous owner becomes an admin. */
public record OwnershipTransferred(ParticipantResponse previousOwner, ParticipantResponse newOwner) {
}
