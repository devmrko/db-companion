package validation;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
public class QualityFailureFixtureTest {
    @Test public void helloResponds() { assertEquals("hello", new QualityFailureFixture().hello()); }
}
