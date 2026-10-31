package com.murmur.userservice.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.murmur.common.Ids;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(UserController.class)
@Import(UserService.class)
class UserControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    UserRepository repository;

    @Test
    void create_returns201_lowercasesHandle() throws Exception {
        mvc.perform(post("/users").contentType(APPLICATION_JSON).content("""
                        {"handle":"Ada","displayName":"Ada"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.handle").value("ada"))
                .andExpect(jsonPath("$.displayName").value("Ada"))
                .andExpect(jsonPath("$.followerCount").doesNotExist());

        var captor = ArgumentCaptor.forClass(User.class);
        verify(repository).insert(captor.capture());
        assertThat(captor.getValue().id().version()).isEqualTo(7);
        assertThat(captor.getValue().handle()).isEqualTo("ada");
    }

    @Test
    void create_returns400_whenHandleInvalid() throws Exception {
        mvc.perform(post("/users").contentType(APPLICATION_JSON).content("""
                        {"handle":"no","displayName":"Ada"}""")).andExpect(status().isBadRequest());

        verifyNoInteractions(repository);
    }

    @Test
    void get_returns404_whenMissing() throws Exception {
        when(repository.findById(any())).thenReturn(Optional.empty());

        mvc.perform(get("/users/" + Ids.newId())).andExpect(status().isNotFound());
    }
}
