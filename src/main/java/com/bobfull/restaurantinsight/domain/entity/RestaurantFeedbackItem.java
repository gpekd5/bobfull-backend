package com.bobfull.restaurantinsight.domain.entity;
import com.bobfull.common.entity.BaseTimeEntity;
import jakarta.persistence.*;
@Entity
@Table(name = "restaurant_feedback_item", uniqueConstraints = @UniqueConstraint(name = "uk_feedback_item_analysis_key", columnNames = {"restaurant_feedback_analysis_id", "category", "aspect_type", "normalized_aspect", "opinion_type", "sentiment"}), indexes = @Index(name = "idx_feedback_item_aggregation", columnList = "category,aspect_type,normalized_aspect,opinion_type,sentiment"))
public class RestaurantFeedbackItem extends BaseTimeEntity {
 @Id @GeneratedValue(strategy = GenerationType.IDENTITY) @Column(name = "restaurant_feedback_item_id") private Long id;
 @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "restaurant_feedback_analysis_id", nullable = false) private RestaurantFeedbackInsight analysis;
 @Enumerated(EnumType.STRING) @Column(nullable=false,length=20) private FeedbackCategory category;
 @Enumerated(EnumType.STRING) @Column(name="aspect_type",nullable=false,length=20) private FeedbackAspectType aspectType;
 @Column(name="normalized_aspect",nullable=false,length=40) private String normalizedAspect;
 @Enumerated(EnumType.STRING) @Column(name="opinion_type",nullable=false,length=20) private FeedbackOpinionType opinionType;
 @Enumerated(EnumType.STRING) @Column(nullable=false,length=20) private FeedbackSentiment sentiment;
 protected RestaurantFeedbackItem() { }
 private RestaurantFeedbackItem(RestaurantFeedbackInsight analysis, FeedbackCategory category,FeedbackAspectType aspectType,String normalizedAspect,FeedbackOpinionType opinionType,FeedbackSentiment sentiment){this.analysis=analysis;this.category=category;this.aspectType=aspectType;this.normalizedAspect=normalizedAspect;this.opinionType=opinionType;this.sentiment=sentiment;}
 static RestaurantFeedbackItem create(RestaurantFeedbackInsight a,FeedbackCategory c,FeedbackAspectType t,String n,FeedbackOpinionType o,FeedbackSentiment s){return new RestaurantFeedbackItem(a,c,t,n,o,s);}
 public FeedbackCategory getCategory() { return category; }
 public FeedbackAspectType getAspectType() { return aspectType; }
 public String getNormalizedAspect() { return normalizedAspect; }
 public FeedbackOpinionType getOpinionType() { return opinionType; }
 public FeedbackSentiment getSentiment() { return sentiment; }
}
