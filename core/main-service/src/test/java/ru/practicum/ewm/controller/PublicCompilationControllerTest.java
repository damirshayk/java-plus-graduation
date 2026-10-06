package ru.practicum.ewm.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;
import ru.practicum.ewm.controller.publ.PublicCompilationController;
import ru.practicum.ewm.dto.compilation.CompilationDto;
import ru.practicum.ewm.service.CompilationService;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PublicCompilationController.class)
class PublicCompilationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private CompilationService compilationService;

    @Test
    void shouldGetCompilations() throws Exception {
        CompilationDto dto = new CompilationDto();
        dto.setId(1L);
        dto.setTitle("Test Compilation");
        dto.setIsPinned(true);

        when(compilationService.getCompilations(null, 0, 10)).thenReturn(List.of(dto));

        mockMvc.perform(get("/compilations")
                        .param("from", "0")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1L))
                .andExpect(jsonPath("$[0].title").value("Test Compilation"));
    }

    @Test
    void shouldGetPinnedCompilations() throws Exception {
        CompilationDto dto = new CompilationDto();
        dto.setId(1L);
        dto.setTitle("isPinned Compilation");
        dto.setIsPinned(true);

        when(compilationService.getCompilations(true, 0, 10)).thenReturn(List.of(dto));

        mockMvc.perform(get("/compilations")
                        .param("isPinned", "true")
                        .param("from", "0")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].pinned").value(true));
    }

    @Test
    void shouldGetCompilationById() throws Exception {
        CompilationDto dto = new CompilationDto();
        dto.setId(1L);
        dto.setTitle("Test Compilation");

        when(compilationService.getCompilation(1L)).thenReturn(dto);

        mockMvc.perform(get("/compilations/{compId}", 1L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1L))
                .andExpect(jsonPath("$.title").value("Test Compilation"));
    }
}
