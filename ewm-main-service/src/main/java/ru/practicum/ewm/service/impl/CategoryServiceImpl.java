package ru.practicum.ewm.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.practicum.ewm.dto.category.CategoryDto;
import ru.practicum.ewm.dto.category.NewCategoryDto;
import ru.practicum.ewm.exception.ConflictException;
import ru.practicum.ewm.exception.NotFoundException;
import ru.practicum.ewm.mapper.CategoryMapper;
import ru.practicum.ewm.model.Category;
import ru.practicum.ewm.repository.CategoryRepository;
import ru.practicum.ewm.repository.EventRepository;
import ru.practicum.ewm.service.CategoryService;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CategoryServiceImpl implements CategoryService {

    private final CategoryRepository categoryRepository;
    private final EventRepository eventRepository;
    private final CategoryMapper categoryMapper;

    @Override
    @Transactional
    public CategoryDto addCategory(NewCategoryDto newCategoryDto) {
        log.info("Добавление категории: {}", newCategoryDto);

        if (categoryRepository.existsByName(newCategoryDto.getName())) {
            throw new ConflictException(
                    String.format("Category with name '%s' already exists", newCategoryDto.getName())
            );
        }

        Category category = categoryMapper.toCategory(newCategoryDto);
        Category saved = categoryRepository.save(category);
        log.info("Категория создана с id={}", saved.getId());

        return categoryMapper.toDto(saved);
    }

    @Override
    @Transactional
    public void deleteCategory(Long catId) {
        log.info("Удаление категории с id={}", catId);

        Category category = categoryRepository.findById(catId)
                .orElseThrow(() -> new NotFoundException(
                        String.format("Category with id %d was not found", catId)
                ));

        if (eventRepository.existsByCategoryId(catId)) {
            throw new ConflictException("The category is not empty");
        }

        categoryRepository.delete(category);
        log.info("Категория с id={} удалена", catId);
    }

    @Override
    @Transactional
    public CategoryDto updateCategory(Long catId, CategoryDto categoryDto) {
        log.info("Обновление категории с id={}, данные={}", catId, categoryDto);

        Category category = categoryRepository.findById(catId)
                .orElseThrow(() -> new NotFoundException(
                        String.format("Category with id %d was not found", catId)
                ));

        if (categoryDto.getName() != null &&
                !categoryDto.getName().equals(category.getName()) &&
                categoryRepository.existsByName(categoryDto.getName())) {
            throw new ConflictException(
                    String.format("Category with name '%s' already exists", categoryDto.getName())
            );
        }

        categoryMapper.updateCategory(category, categoryDto);
        Category updated = categoryRepository.save(category);
        log.info("Категория с id={} обновлена", catId);

        return categoryMapper.toDto(updated);
    }

    @Override
    public List<CategoryDto> getCategories(Integer from, Integer size) {
        log.info("Получение категорий: from={}, size={}", from, size);

        Pageable pageable = PageRequest.of(from / size, size);
        List<Category> categories = categoryRepository.findAll(pageable).getContent();

        return categories.stream()
                .map(categoryMapper::toDto)
                .collect(Collectors.toList());
    }

    @Override
    public CategoryDto getCategory(Long catId) {
        log.info("Получение категории с id={}", catId);

        Category category = categoryRepository.findById(catId)
                .orElseThrow(() -> new NotFoundException(
                        String.format("Category with id %d was not found", catId)
                ));

        return categoryMapper.toDto(category);
    }
}
