package com.mywatchmate.church;

import java.util.UUID;

/** Things staff should hear about, published inside the transaction that caused them. */
final class ChurchEvents {

    private ChurchEvents() {
    }

    record Applied(UUID churchId) {
    }

    record ChangeRequested(UUID churchId) {
    }
}
