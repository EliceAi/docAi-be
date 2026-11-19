package com.team.docai.domain.comparison.entity;
import jakarta.persistence.*;
import lombok.*;
@Entity @Table(name="comparison_change") @Getter @NoArgsConstructor(access=AccessLevel.PROTECTED)
public class ComparisonChange {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long changeId;
 @Column(nullable=false) private Long comparisonId;
 @Column(nullable=false,length=20) private String type;
 @Column(nullable=false,length=10) private String category;
 @Column(nullable=false,length=100) private String label;
 private String beforeText;
 private String afterText;
 @Column(length=200) private String note;
 @Column(nullable=false,length=10) private String source;
 @Column(length=100) private String location;
 @Column(length=200) private String reason;
 @Builder(access=AccessLevel.PRIVATE)
 private ComparisonChange(Long comparisonId,String type,String category,String label,String beforeText,String afterText,String note,String source,String location,String reason){
  this.comparisonId=comparisonId;this.type=type;this.category=category;this.label=label;this.beforeText=beforeText;
  this.afterText=afterText;this.note=note;this.source=source;this.location=location;this.reason=reason;
 }
 public static ComparisonChange createChange(Long comparisonId,String type,String category,String label,String beforeText,String afterText,String note,String source){
  return ComparisonChange.builder().comparisonId(comparisonId).type(type).category(category).label(label)
   .beforeText(beforeText).afterText(afterText).note(note).source(source).build();
 }
 public static ComparisonChange createUncompared(Long comparisonId,String category,String label,String beforeText,String location,String reason){
  return ComparisonChange.builder().comparisonId(comparisonId).type("UNCOMPARED").category(category).label(label)
   .beforeText(beforeText).location(location).reason(reason).source("WRITTEN").build();
 }
 public void applyManualInput(String text,boolean previousSide){
  boolean wasUncompared="UNCOMPARED".equals(this.type);
  this.type="CHANGED";this.source="MANUAL";
  if(wasUncompared&&previousSide){this.afterText=this.beforeText;this.beforeText=text;}
  else if(previousSide){this.beforeText=text;}
  else{this.afterText=text;}
  this.note="내가 직접 입력한 내용이에요. 원문 글자를 다시 읽은 결과는 아니에요.";
 }
}
