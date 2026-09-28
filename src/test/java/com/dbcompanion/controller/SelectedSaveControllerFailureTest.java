package com.dbcompanion.controller;

import com.dbcompanion.model.AiAssistant;
import com.dbcompanion.model.Ontology;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** Controller classification only: no persistence, provider, or database call. */
class SelectedSaveControllerFailureTest {
    @Test void knownOntologyFailureAbortsAndPreservesItsStatus(){
        var aborted=new AtomicInteger();var unconfirmed=new AtomicInteger();
        var failure=catchThrowable(()->SelectAiTestController.resolveSaveFailure(new Ontology.Failure(409,"mismatch"),aborted::incrementAndGet,unconfirmed::incrementAndGet));
        assertThat(failure).isInstanceOf(Ontology.Failure.class);assertThat(((Ontology.Failure)failure).status()).isEqualTo(409);
        assertThat(aborted).hasValue(1);assertThat(unconfirmed).hasValue(0);
    }
    @Test void knownAiFailureAlsoAbortsButUnknownIoIsUnconfirmed(){
        var aborted=new AtomicInteger();var unconfirmed=new AtomicInteger();
        var known=catchThrowable(()->SelectAiTestController.resolveSaveFailure(new AiAssistant.Failure(413,"test","known"),aborted::incrementAndGet,unconfirmed::incrementAndGet));
        assertThat(known).isInstanceOf(AiAssistant.Failure.class);assertThat(((AiAssistant.Failure)known).status()).isEqualTo(413);assertThat(aborted).hasValue(1);
        var unknown=catchThrowable(()->SelectAiTestController.resolveSaveFailure(new IllegalStateException("synthetic I/O"),aborted::incrementAndGet,unconfirmed::incrementAndGet));
        assertThat(unknown).isInstanceOf(AiAssistant.Failure.class);assertThat(((AiAssistant.Failure)unknown).status()).isEqualTo(503);assertThat(unconfirmed).hasValue(1);
    }
    @Test void allFourSelectedSaveEndpointsUseTheSameClassificationGate() throws Exception {
        var source=Files.readString(Path.of("src/main/java/com/dbcompanion/controller/SelectAiTestController.java"));
        assertThat(source.split("resolveSaveFailure\\(ex",-1)).hasSize(5);
    }
}
