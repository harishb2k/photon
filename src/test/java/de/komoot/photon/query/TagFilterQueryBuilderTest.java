package de.komoot.photon.query;

import de.komoot.photon.utils.QueryToJson;
import org.apache.commons.io.IOUtils;
import org.codehaus.jackson.JsonNode;
import org.codehaus.jackson.map.ObjectMapper;
import org.codehaus.jackson.map.SerializationConfig;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.xcontent.ToXContent;
import org.elasticsearch.common.xcontent.json.JsonXContent;
import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;

public class TagFilterQueryBuilderTest {

    @Test
    public void testConstructor() throws IOException {
        TagFilterQueryBuilder photonQueryBuilder = PhotonQueryBuilder.builder("berlin", "en");
        InputStream resourceAsStream = this.getClass().getClassLoader()
                .getResourceAsStream("json_queries/test_base_query.json");
        String expectedJsonString = IOUtils.toString(resourceAsStream, Charset.forName("utf-8"));

        String actualJsonString = new QueryToJson().convert(photonQueryBuilder.buildQuery());
        JsonNode actualJson = this.readJson(actualJsonString);
        JsonNode expectedJson = this.readJson(expectedJsonString);
        Assert.assertEquals(expectedJson, actualJson);
    }

    @Test
    public void testFrenchConstructor() throws IOException {
        TagFilterQueryBuilder photonQueryBuilder = PhotonQueryBuilder.builder("berlin", "fr");
        InputStream resourceAsStream = this.getClass().getClassLoader()
                .getResourceAsStream("json_queries/test_base_query_fr.json");
        String expectedJsonString = IOUtils.toString(resourceAsStream, Charset.forName("utf-8"));

        String actualJsonString = new QueryToJson().convert(photonQueryBuilder.buildQuery());
        JsonNode actualJson = this.readJson(actualJsonString);
        JsonNode expectedJson = this.readJson(expectedJsonString);
        Assert.assertEquals(expectedJson, actualJson);
    }

    private JsonNode readJson(ToXContent queryBuilder) throws IOException {
        // return this.readJson(
        //     queryBuilder.toXContent(JsonXContent.contentBuilder(), new ToXContent.MapParams(null)).bytes());
        // FIXME - Above code did not compile for ES 7 - so added the following fix
        return this.readJson(
                Strings.toString(queryBuilder.toXContent(JsonXContent.contentBuilder(), new ToXContent.MapParams(null)))
        );
    }

    private JsonNode readJson(BytesReference jsonStringBytes) throws IOException {
        return this.readJson(jsonStringBytes.utf8ToString());
    }

    private JsonNode readJson(String jsonString) throws IOException {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.configure(SerializationConfig.Feature.INDENT_OUTPUT, true);
        return objectMapper.readTree(jsonString);
    }
}
