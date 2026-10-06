package ru.practicum.ewm.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import ru.practicum.ewm.dto.category.CategoryDto;
import ru.practicum.ewm.dto.category.NewCategoryDto;
import ru.practicum.ewm.exception.ConflictException;
import ru.practicum.ewm.exception.NotFoundException;
import ru.practicum.ewm.mapper.CategoryMapper;
import ru.practicum.ewm.model.Category;
import ru.practicum.ewm.repository.CategoryRepository;
import ru.practicum.ewm.repository.EventRepository;
import ru.practicum.ewm.service.impl.CategoryServiceImpl;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CategoryServiceImplTest {

    @Mock
    private CategoryRepository categoryRepository;

    @Mock
    private EventRepository eventRepository;

    @Mock
    private CategoryMapper categoryMapper;

    @InjectMocks
    private CategoryServiceImpl categoryService;

    private Category category;
    private CategoryDto categoryDto;
    private NewCategoryDto newCategoryDto;

    @BeforeEach
    void setUp() {
        category = new Category();
        category.setId(1L);
        category.setName("Test Category");

        categoryDto = new CategoryDto();
        categoryDto.setId(1L);
        categoryDto.setName("Test Category");

        newCategoryDto = new NewCategoryDto();
        newCategoryDto.setName("Test Category");
    }

    @Test
    void shouldAddCategory() {
        when(categoryRepository.existsByName(newCategoryDto.getName())).thenReturn(false);
        when(categoryMapper.toCategory(newCategoryDto)).thenReturn(category);
        when(categoryRepository.save(any(Category.class))).thenReturn(category);
        when(categoryMapper.toDto(any(Category.class))).thenReturn(categoryDto);

        CategoryDto result = categoryService.addCategory(newCategoryDto);

        assertThat(result).isNotNull();
        assertThat(result.getName()).isEqualTo("Test Category");
        verify(categoryRepository).save(any(Category.class));
    }

    @Test
    void shouldThrowConflictExceptionWhenCategoryNameExists() {
        when(categoryRepository.existsByName(newCategoryDto.getName())).thenReturn(true);

        assertThatThrownBy(() -> categoryService.addCategory(newCategoryDto))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Category with name 'Test Category' already exists");

        verify(categoryRepository, never()).save(any(Category.class));
    }

    @Test
    void shouldDeleteCategory() {
        when(categoryRepository.findById(1L)).thenReturn(Optional.of(category));
        when(eventRepository.existsByCategoryId(1L)).thenReturn(false);
        doNothing().when(categoryRepository).delete(any(Category.class));

        categoryService.deleteCategory(1L);

        verify(categoryRepository).delete(any(Category.class));
    }

    @Test
    void shouldThrowNotFoundExceptionWhenDeletingNonExistingCategory() {
        when(categoryRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> categoryService.deleteCategory(99L))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("Category with id 99 was not found");

        verify(categoryRepository, never()).delete(any(Category.class));
    }

    @Test
    void shouldThrowConflictExceptionWhenDeletingCategoryWithEvents() {
        when(categoryRepository.findById(1L)).thenReturn(Optional.of(category));
        when(eventRepository.existsByCategoryId(1L)).thenReturn(true);

        assertThatThrownBy(() -> categoryService.deleteCategory(1L))
                .isInstanceOf(ConflictException.class)
                .hasMessage("The category is not empty");

        verify(categoryRepository, never()).delete(any(Category.class));
    }

    @Test
    void shouldUpdateCategory() {
        CategoryDto updateDto = new CategoryDto();
        updateDto.setName("Updated Name");

        when(categoryRepository.findById(1L)).thenReturn(Optional.of(category));
        when(categoryRepository.existsByName("Updated Name")).thenReturn(false);
        doNothing().when(categoryMapper).updateCategory(any(Category.class), any(CategoryDto.class));
        when(categoryRepository.save(any(Category.class))).thenReturn(category);
        when(categoryMapper.toDto(any(Category.class))).thenReturn(updateDto);

        CategoryDto result = categoryService.updateCategory(1L, updateDto);

        assertThat(result).isNotNull();
        assertThat(result.getName()).isEqualTo("Updated Name");
        verify(categoryRepository).save(any(Category.class));
    }

    @Test
    void shouldThrowConflictExceptionWhenUpdatingToExistingName() {
        CategoryDto updateDto = new CategoryDto();
        updateDto.setName("Existing Name");

        when(categoryRepository.findById(1L)).thenReturn(Optional.of(category));
        when(categoryRepository.existsByName("Existing Name")).thenReturn(true);

        assertThatThrownBy(() -> categoryService.updateCategory(1L, updateDto))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Category with name 'Existing Name' already exists");

        verify(categoryRepository, never()).save(any(Category.class));
    }

    @Test
    void shouldGetCategories() {
        List<Category> categories = List.of(category);
        Page<Category> page = new PageImpl<>(categories);

        when(categoryRepository.findAll(PageRequest.of(0, 10))).thenReturn(page);
        when(categoryMapper.toDto(any(Category.class))).thenReturn(categoryDto);

        List<CategoryDto> result = categoryService.getCategories(0, 10);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getName()).isEqualTo("Test Category");
    }

    @Test
    void shouldGetCategoryById() {
        when(categoryRepository.findById(1L)).thenReturn(Optional.of(category));
        when(categoryMapper.toDto(any(Category.class))).thenReturn(categoryDto);

        CategoryDto result = categoryService.getCategory(1L);

        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo(1L);
        assertThat(result.getName()).isEqualTo("Test Category");
    }

    @Test
    void shouldThrowNotFoundExceptionWhenGettingNonExistingCategory() {
        when(categoryRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> categoryService.getCategory(99L))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("Category with id 99 was not found");
    }
}
