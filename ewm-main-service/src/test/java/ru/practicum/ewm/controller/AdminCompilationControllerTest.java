package ru.practicum.ewm.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import ru.practicum.ewm.controller.admin.AdminCompilationController;
import ru.practicum.ewm.dto.compilation.CompilationDto;
import ru.practicum.ewm.dto.compilation.NewCompilationDto;
import ru.practicum.ewm.dto.compilation.UpdateCompilationRequest;
import ru.practicum.ewm.service.CompilationService;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminCompilationController.class)
class AdminCompilationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private CompilationService compilationService;

    @Test
    void shouldCreateCompilation() throws Exception {
        NewCompilationDto newDto = NewCompilationDto.builder()
                .title("Test Compilation")
                .isPinned(true)
                .build();

        CompilationDto resultDto = new CompilationDto();
        resultDto.setId(1L);
        resultDto.setTitle("Test Compilation");
        resultDto.setIsPinned(true);

        when(compilationService.create(any(NewCompilationDto.class))).thenReturn(resultDto);

        mockMvc.perform(post("/admin/compilations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(newDto)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1L))
                .andExpect(jsonPath("$.title").value("Test Compilation"));

        verify(compilationService).create(any(NewCompilationDto.class));
    }

    @Test
    void shouldDeleteCompilation() throws Exception {
        doNothing().when(compilationService).delete(1L);

        mockMvc.perform(delete("/admin/compilations/{compId}", 1L))
                .andExpect(status().isNoContent());

        verify(compilationService).delete(1L);
    }

    @Test
    void shouldUpdateCompilation() throws Exception {
        UpdateCompilationRequest request = new UpdateCompilationRequest();
        request.setTitle("Updated Compilation");

        CompilationDto resultDto = new CompilationDto();
        resultDto.setId(1L);
        resultDto.setTitle("Updated Compilation");

        when(compilationService.update(eq(1L), any(UpdateCompilationRequest.class))).thenReturn(resultDto);

        mockMvc.perform(patch("/admin/compilations/{compId}", 1L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1L))
                .andExpect(jsonPath("$.title").value("Updated Compilation"));

        verify(compilationService).update(eq(1L), any(UpdateCompilationRequest.class));
    }
}
