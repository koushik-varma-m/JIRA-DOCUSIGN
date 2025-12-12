package ut.com.koushik.docusign;

import org.junit.Test;
import com.koushik.docusign.api.MyPluginComponent;
import com.koushik.docusign.impl.MyPluginComponentImpl;

import static org.junit.Assert.assertEquals;

public class MyComponentUnitTest {
    @Test
    public void testMyName() {
        MyPluginComponent component = new MyPluginComponentImpl(null);
        assertEquals("names do not match!", "myComponent", component.getName());
    }
}