package com.team.docai.domain.contract;
import com.team.docai.domain.contract.service.ContractService;
import com.team.docai.domain.contract.entity.Contract;
import com.team.docai.domain.contract.repository.*;
import com.team.docai.domain.schedule.service.ScheduleService;
import com.team.docai.domain.schedule.repository.ScheduleRepository;
import com.team.docai.domain.documenttype.service.DocumentTypeService;
import com.team.docai.global.storage.FileStorage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.Clock;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
class ServiceWiringTest {
 @Test void contractAndScheduleCanBeCreatedAndCalledWithoutCircularDependency(){
  var contracts=mock(ContractRepository.class);var schedules=mock(ScheduleRepository.class);var user=UUID.randomUUID();var contract=Contract.create(user,"EMPLOYMENT");
  ReflectionTestUtils.setField(contract,"contractId",1L);ReflectionTestUtils.setField(contract,"archived",true);
  when(contracts.findByContractIdAndUserIdAndDeletedAtIsNull(1L,user)).thenReturn(Optional.of(contract));when(schedules.countByUserIdAndContractId(user,1L)).thenReturn(2L);
  new ApplicationContextRunner().withBean(ContractRepository.class,()->contracts).withBean(ScheduleRepository.class,()->schedules)
   .withBean(ContractVersionRepository.class,()->mock(ContractVersionRepository.class)).withBean(ContractPageRepository.class,()->mock(ContractPageRepository.class))
   .withBean(DocumentTypeService.class,()->mock(DocumentTypeService.class)).withBean(FileStorage.class,()->mock(FileStorage.class))
   .withBean(JdbcTemplate.class,()->mock(JdbcTemplate.class)).withBean(ObjectMapper.class,ObjectMapper::new).withBean(Clock.class,Clock::systemUTC)
   .withBean(ContractService.class).withBean(ScheduleService.class).run(ctx->{
    assertNull(ctx.getStartupFailure());var result=ctx.getBean(ContractService.class).getSimilarContractInfo(user,1L).orElseThrow();assertEquals(2,result.scheduleCount());
   });
 }
}
