package com.team.docai.integration;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.*;
import org.springframework.mock.web.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.util.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest @AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named="DOCAI_INTEGRATION_TESTS", matches="true")
class AppFlowTest {
    @Autowired MockMvc mvc; @Autowired ObjectMapper mapper;
    @DynamicPropertySource static void db(DynamicPropertyRegistry r){
        r.add("spring.datasource.url",()->System.getenv("TEST_DB_URL"));
        r.add("spring.datasource.username",()->System.getenv("DB_USERNAME"));
        r.add("spring.datasource.password",()->System.getenv("DB_PASSWORD"));
        r.add("docai.storage-path",()->System.getenv("TEST_STORAGE_PATH"));
        r.add("docai.ai-mode",()->"mock");
    }
    JsonNode data(MvcResult r)throws Exception{return mapper.readTree(r.getResponse().getContentAsString()).path("data");}
    MockHttpSession account()throws Exception{
        String email=UUID.randomUUID()+"@example.com";
        String body=mapper.writeValueAsString(Map.of("name","시연","email",email,"password","test-password"));
        mvc.perform(post("/api/auth/registrations").with(csrf()).contentType("application/json").content(body)).andExpect(status().isCreated());
        var result=mvc.perform(post("/api/auth/sessions").with(csrf()).contentType("application/json").content(body)).andExpect(status().isOk()).andReturn();
        return (MockHttpSession)result.getRequest().getSession(false);
    }
    byte[] image()throws Exception{var c=new BufferedImage(40,40,BufferedImage.TYPE_INT_RGB);var out=new ByteArrayOutputStream();ImageIO.write(c,"png",out);return out.toByteArray();}
    JsonNode create(MockHttpSession s,String type,String scenario,UUID key)throws Exception{
        return data(mvc.perform(multipart("/api/checks").file(new MockMultipartFile("pages","masked.png","image/png",image())).param("title","시연 계약서").param("documentType",type).param("requestKey",key.toString()).param("confirmed","true").param("scenario",scenario).session(s).with(csrf())).andExpect(status().isCreated()).andReturn());
    }
    JsonNode complete(MockHttpSession s,String type,String scenario)throws Exception{
        String id=create(s,type,scenario,UUID.randomUUID()).path("id").asText();
        for(int i=0;i<100;i++){var d=data(mvc.perform(get("/api/checks/"+id).session(s)).andExpect(status().isOk()).andReturn());if(!List.of("PENDING","RUNNING").contains(d.path("status").asText()))return d;Thread.sleep(50);}
        throw new AssertionError("analysis did not finish");
    }
    JsonNode archive(MockHttpSession s,String id,String body)throws Exception{
        return data(mvc.perform(post("/api/checks/"+id+"/archives").session(s).with(csrf()).contentType("application/json").content(body)).andExpect(status().isOk()).andReturn());
    }
    @Test void unauthenticatedAndCsrfRequestsAreRejected()throws Exception{
        mvc.perform(get("/api/checks")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/registrations").contentType("application/json").content("{}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/auth/sessions").with(csrf()).contentType("application/json").content("{\"email\":\"missing@example.com\",\"password\":\"password\"}")).andExpect(status().isUnauthorized());
    }
    @Test void catalogContainsExactlyTwoAvailableAndTwentyOnePlannedDocuments()throws Exception{
        var s=account();var list=data(mvc.perform(get("/api/document-types").session(s)).andExpect(status().isOk()).andReturn());
        assertThat(list.size()).isEqualTo(23);int available=0;for(var d:list)if(d.path("available").asBoolean())available++;assertThat(available).isEqualTo(2);
    }
    @Test void employmentAndLeaseFlowWithIdempotentArchiving()throws Exception{
        var s=account();for(String type:List.of("EMPLOYMENT","LEASE")){
            var d=complete(s,type,"normal");assertThat(d.path("status").asText()).isEqualTo("COMPLETED");assertThat(d.path("analysis").path("mode").asText()).isEqualTo("MOCK");
            String body="{\"schedules\":[{\"key\":\"start\",\"date\":\"2026-12-01\"}]}";
            archive(s,d.path("id").asText(),body);archive(s,d.path("id").asText(),body);
        }
        assertThat(data(mvc.perform(get("/api/schedules").session(s)).andReturn()).size()).isEqualTo(2);
        assertThat(data(mvc.perform(get("/api/checks?archived=true").session(s)).andReturn()).size()).isEqualTo(2);
    }
    @Test void uploadRequestKeyPreventsDuplicateDocuments()throws Exception{
        var s=account();var key=UUID.randomUUID();var a=create(s,"LEASE","normal",key);var b=create(s,"LEASE","normal",key);assertThat(a.path("id")).isEqualTo(b.path("id"));
        assertThat(data(mvc.perform(get("/api/checks").session(s)).andReturn()).size()).isEqualTo(1);
    }
    @Test void foreignUserCannotReadImagesResultsArchiveOrChat()throws Exception{
        var owner=account();var stranger=account();var d=complete(owner,"LEASE","normal");String id=d.path("id").asText();
        mvc.perform(get("/api/checks/"+id).session(stranger)).andExpect(status().isNotFound());
        mvc.perform(get("/api/checks/"+id+"/pages/0").session(stranger)).andExpect(status().isNotFound());
        mvc.perform(post("/api/checks/"+id+"/archives").session(stranger).with(csrf()).contentType("application/json").content("{\"schedules\":[]}")).andExpect(status().isNotFound());
        mvc.perform(post("/api/chats").session(stranger).with(csrf()).contentType("application/json").content("{\"documentId\":\""+id+"\"}")).andExpect(status().isNotFound());
        assertThat(data(mvc.perform(get("/api/checks").session(stranger)).andReturn()).isEmpty()).isTrue();
    }
    @Test void invalidFilesUnsupportedTypeAndUnconfirmedUploadAreRejected()throws Exception{
        var s=account();
        mvc.perform(multipart("/api/checks").file(new MockMultipartFile("pages","x.png","image/png",new byte[]{1,2})).param("title","x").param("documentType","LEASE").param("requestKey",UUID.randomUUID().toString()).param("confirmed","true").param("scenario","normal").session(s).with(csrf())).andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/checks").file(new MockMultipartFile("pages","x.png","image/png",image())).param("title","x").param("documentType","OTHER").param("requestKey",UUID.randomUUID().toString()).param("confirmed","true").param("scenario","normal").session(s).with(csrf())).andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/checks").file(new MockMultipartFile("pages","x.png","image/png",image())).param("title","x").param("documentType","LEASE").param("requestKey",UUID.randomUUID().toString()).param("confirmed","false").param("scenario","normal").session(s).with(csrf())).andExpect(status().isBadRequest());
    }
    @Test void failedAnalysisIsNotArchivedAndCanBeRetried()throws Exception{
        var s=account();var d=complete(s,"EMPLOYMENT","failure");String id=d.path("id").asText();assertThat(d.path("status").asText()).isEqualTo("FAILED");
        mvc.perform(post("/api/checks/"+id+"/archives").session(s).with(csrf()).contentType("application/json").content("{\"schedules\":[]}")).andExpect(status().isConflict());
        mvc.perform(post("/api/checks/"+id+"/retries").session(s).with(csrf())).andExpect(status().isOk());
        for(int i=0;i<100;i++){d=data(mvc.perform(get("/api/checks/"+id).session(s)).andReturn());if(d.path("status").asText().equals("COMPLETED"))break;Thread.sleep(50);}
        assertThat(d.path("status").asText()).isEqualTo("COMPLETED");
    }
    @Test void missingDateCannotBeSavedAndManualDatesHaveProvenance()throws Exception{
        var s=account();var d=complete(s,"LEASE","normal");String id=d.path("id").asText();
        mvc.perform(post("/api/checks/"+id+"/archives").session(s).with(csrf()).contentType("application/json").content("{\"schedules\":[{\"key\":\"review\"}]}")).andExpect(status().isBadRequest());
        archive(s,id,"{\"schedules\":[{\"key\":\"review\",\"date\":\"2027-01-05\"}]}");
        assertThat(data(mvc.perform(get("/api/schedules").session(s)).andReturn()).get(0).path("source").asText()).isEqualTo("직접 입력");
    }
    @Test void comparisonRequiresSameTypeAndConfirmationAndPreservesOldVersion()throws Exception{
        var s=account();var old=complete(s,"LEASE","normal");String a=old.path("id").asText();archive(s,a,"{\"schedules\":[{\"key\":\"start\",\"date\":\"2026-12-01\"}]}");
        var next=complete(s,"LEASE","revised");String b=next.path("id").asText();
        var cmp=data(mvc.perform(get("/api/comparisons").param("previousId",a).param("currentId",b).session(s)).andExpect(status().isOk()).andReturn());assertThat(cmp.path("changes").toString()).contains("CHANGED");
        String content="{\"previousId\":\""+a+"\",\"sameContractConfirmed\":true,\"schedules\":[]}";
        mvc.perform(post("/api/checks/"+b+"/archives").session(s).with(csrf()).contentType("application/json").content(content.replace("true","false"))).andExpect(status().isBadRequest());
        var archived=archive(s,b,content);assertThat(archived.path("revision").asInt()).isEqualTo(2);assertThat(archived.path("previousId").asText()).isEqualTo(a);
        assertThat(data(mvc.perform(get("/api/checks/"+a).session(s)).andReturn()).path("archived").asBoolean()).isTrue();
        assertThat(data(mvc.perform(get("/api/schedules").session(s)).andReturn()).isEmpty()).isTrue();
        var work=complete(s,"EMPLOYMENT","normal");mvc.perform(get("/api/comparisons").param("previousId",a).param("currentId",work.path("id").asText()).session(s)).andExpect(status().isBadRequest());
    }
    @Test void partialComparisonInputsRemainExplicitlyManual()throws Exception{
        var s=account();String a=complete(s,"LEASE","normal").path("id").asText();archive(s,a,"{\"schedules\":[]}");String b=complete(s,"LEASE","partial").path("id").asText();
        String body=mapper.writeValueAsString(Map.of("previousId",a,"currentId",b,"label","관리비","before","50,000원","after","60,000원"));
        var result=data(mvc.perform(post("/api/comparisons/inputs").session(s).with(csrf()).contentType("application/json").content(body)).andExpect(status().isOk()).andReturn());assertThat(result.toString()).contains("직접 입력","60,000원");
    }
    @Test void chatIsolationAndIdempotentMessageHandling()throws Exception{
        var s=account();var stranger=account();String id=complete(s,"LEASE","normal").path("id").asText();
        var chat=data(mvc.perform(post("/api/chats").session(s).with(csrf()).contentType("application/json").content("{\"documentId\":\""+id+"\"}")).andExpect(status().isCreated()).andReturn());String cid=chat.path("id").asText();
        String body=mapper.writeValueAsString(Map.of("question","관리비는?","requestKey",UUID.randomUUID()));
        for(int i=0;i<2;i++)mvc.perform(post("/api/chats/"+cid+"/messages").session(s).with(csrf()).contentType("application/json").content(body)).andExpect(status().isOk());
        assertThat(data(mvc.perform(get("/api/chats/"+cid).session(s)).andReturn()).path("messages").size()).isEqualTo(1);
        mvc.perform(get("/api/chats/"+cid).session(stranger)).andExpect(status().isNotFound());
    }
    @Test void logoutInvalidatesSession()throws Exception{
        var s=account();mvc.perform(post("/api/auth/logout").session(s).with(csrf())).andExpect(status().isOk());assertThat(s.isInvalid()).isTrue();
    }
}
