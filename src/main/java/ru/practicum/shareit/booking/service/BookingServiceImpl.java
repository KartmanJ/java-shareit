package ru.practicum.shareit.booking.service;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.practicum.shareit.booking.dto.BookingDto;
import ru.practicum.shareit.booking.dto.BookingDtoInput;
import ru.practicum.shareit.booking.dto.BookingMapper;
import ru.practicum.shareit.booking.model.Booking;
import ru.practicum.shareit.booking.model.BookingStatus;
import ru.practicum.shareit.booking.repository.BookingRepository;
import ru.practicum.shareit.exceptions.BookingException;
import ru.practicum.shareit.exceptions.NotFoundException;
import ru.practicum.shareit.exceptions.UnsupportedBookingStateException;
import ru.practicum.shareit.item.dao.ItemRepository;
import ru.practicum.shareit.user.dao.UserRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
@Slf4j
@AllArgsConstructor
public class BookingServiceImpl {
    private final BookingRepository bookingRepository;
    private final UserRepository userRepository;
    private final ItemRepository itemRepository;

    private final BookingMapper bookingMapper;

    public BookingDto add(BookingDtoInput bookingDto, Long bookerId) {
        isUserExistsCheck(bookerId);
        if (bookingDto.getStart().isAfter(bookingDto.getEnd())) {
            String message = "Начало аренды не может быть после её окончания.";
            log.warn(message);
            throw new BookingException(message);
        }
        if (itemRepository.getById(bookingDto.getItemId()) == null) {
            String message = "Предмет с id=" + bookingDto.getItemId() + " не найден.";
            log.warn(message);
            throw new NotFoundException(message);
        }
        Booking booking = bookingMapper.fromBookingDto(bookingDto, bookerId);
        if (booking.getItem().getOwner().getId().equals(bookerId)) {
            String message = "Владелец предмета не может арендовать собственные предметы.";
            log.warn(message);
            throw new NotFoundException(message);//тут тоже лучше было бы BookingException, но надо 404.
        }
        if (!booking.getItem().getAvailable()) {
            String message = "Предмет с id=" + booking.getItem().getId() + " не доступен для аренды.";
            log.warn(message);
            throw new BookingException(message);
        }
        if (bookingRepository.findInBookingNowByItemId(bookingDto.getItemId()).stream()
                .anyMatch(e -> e.getStart().isBefore(LocalDateTime.now()) & e.getEnd().isAfter(LocalDateTime.now()))) {
            String message = "Предмет с id=" + booking.getItem().getId() + " уже находится в аренде. Попробуйте позднее.";
            log.warn(message);
            throw new BookingException(message);
        }
        log.info("Запрос на аренду предмета '" + booking.getItem().getName() + "' (id=" + booking.getItem().getId()
                + ") был успешно добавлен!");
        return bookingMapper.toBookingDto(bookingRepository.save(booking));
    }

    public BookingDto approve(Long bookingId, Long ownerId, Boolean approve) {
        isUserExistsCheck(ownerId);
        if (bookingRepository.findById(bookingId).isEmpty()) {
            String message = "Запрос с id=" + bookingId + " на аренду предмета не найден.";
            log.warn(message);
            throw new NotFoundException(message);
        }
        Booking booking = bookingRepository.getReferenceById(bookingId);
        if (!booking.getItem().getOwner().getId().equals(ownerId)) {
            String message = "Подтверждать или отклонять запросы на аренду предметов может только их владелец!";
            log.warn(message);
            throw new NotFoundException(message);//тут больше подошёл бы BookingException, но тесты просят код 404.
        }
        if (booking.getStatus().equals(BookingStatus.APPROVED)) {
            String message = "Запрос на аренду предмета уже был подтверждён.";
            log.warn(message);
            throw new BookingException(message);
        }
        if (approve) {
            booking.setStatus(BookingStatus.APPROVED);
            log.info("Запрос на аренду предмета '" + booking.getItem().getName() + "' (id=" + booking.getItem().getId()
                    + ") был успешно подтверждён владельцем!");
        } else {
            booking.setStatus(BookingStatus.REJECTED);
            log.info("Запрос на аренду предмета '" + booking.getItem().getName() + "' (id=" + booking.getItem().getId()
                    + ") был отклонён владельцем!");
        }
        return bookingMapper.toBookingDto(bookingRepository.save(booking));
    }

    public BookingDto get(Long bookingId, Long userId) {
        isUserExistsCheck(userId);
        if (bookingRepository.findById(bookingId).isEmpty()) {
            String message = "Запрос с id=" + bookingId + " на аренду предмета не найден.";
            log.warn(message);
            throw new NotFoundException(message);
        } else {
            Booking booking = bookingRepository.findById(bookingId).get();
            if (booking.getItem().getOwner().getId().equals(userId)) {
                log.info("Запрос на аренду предмета '" + booking.getItem().getName() + "' (id=" + booking.getItem().getId()
                        + ") был успешно получен.");
                return bookingMapper.toBookingDto(booking);
            } else if (booking.getBooker().getId().equals(userId)) {
                log.info("Запрос на аренду предмета '" + booking.getItem().getName() + "' (id=" + booking.getItem().getId()
                        + ") был успешно получен.");
                return bookingMapper.toBookingDto(booking);
            } else {
                String message = "Просматривать запрос на аренду предмета может только автор запроса " +
                        "или владелец предмета!";
                log.warn(message);
                throw new NotFoundException(message);//надоело плодить эксепшены под тесты постмана
                //тут больше подойдёт ошибка авторизации или что-то в этом духе
            }
        }
    }

    public List<BookingDto> getAllByBooker(Long userId, String state) {
        isUserExistsCheck(userId);
        return sortByState(bookingRepository.findAllByBookerSortByStartDate(userId), state);
    }

    public List<BookingDto> getAllByOwner(Long ownerId, String state) {
        isUserExistsCheck(ownerId);
        return sortByState(bookingRepository.findAllByOwnerSortByStartDate(ownerId), state);
    }

    private List<BookingDto> sortByState(List<Booking> bookings, String state) {
        if ("ALL".equals(state)) {
            return bookings.stream()
                    .map(bookingMapper::toBookingDto).collect(Collectors.toList());
        } else if ("CURRENT".equals(state)) {
            return bookings.stream()
                    .filter(e -> e.getStart().isBefore(LocalDateTime.now()) & e.getEnd().isAfter(LocalDateTime.now()))
                    .map(bookingMapper::toBookingDto).collect(Collectors.toList());
        } else if ("PAST".equals(state)) {
            return bookings.stream()
                    .filter(e -> e.getEnd().isBefore(LocalDateTime.now()))
                    .map(bookingMapper::toBookingDto).collect(Collectors.toList());
        } else if ("FUTURE".equals(state)) {
            return bookings.stream()
                    .filter(e -> e.getStart().isAfter(LocalDateTime.now()))
                    .map(bookingMapper::toBookingDto).collect(Collectors.toList());
        } else if ("WAITING".equals(state)) {
            return bookings.stream()
                    .filter(e -> e.getStatus().equals(BookingStatus.WAITING))
                    .map(bookingMapper::toBookingDto).collect(Collectors.toList());
        } else if ("REJECTED".equals(state)) {
            return bookings.stream()
                    .filter(e -> e.getStatus().equals(BookingStatus.REJECTED))
                    .map(bookingMapper::toBookingDto).collect(Collectors.toList());
        } else {
            String message = "Поиск по запросу '" + state + "' не поддерживается.";
            log.warn(message);
            throw new UnsupportedBookingStateException("Unknown state: " + state);
        }
    }

    private void isUserExistsCheck(Long userId) {
        if (userRepository.findById(userId).isEmpty()) {
            String message = "Пользователь с id=" + userId + " не найден.";
            log.warn(message);
            throw new NotFoundException(message);
        }
    }

}